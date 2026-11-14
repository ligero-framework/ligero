package com.ligero.server.loom;

import com.ligero.http.HttpHandler;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ligero's own HTTP server: HTTP/1.1 and WebSockets implemented directly on
 * sockets, with one virtual thread per connection.
 *
 * <p>The design is deliberately simple. A connection is a loop on its own
 * thread — read a request, run the pipeline, write the response — using plain
 * blocking I/O. Nothing is handed between threads, so there is no dispatcher
 * to queue behind; requests are parsed straight from a per-connection buffer
 * and a response is normally a single write.</p>
 *
 * <p>Threads are assigned adaptively. The first
 * {@link Limits#dedicatedThreads()} concurrent connections each get a
 * dedicated platform thread: a blocked read is then woken directly by the
 * kernel, with no scheduler in between, which is what makes the hot path
 * fast. Connections beyond that run on virtual threads, so tens of thousands
 * of mostly idle keep-alive connections cost almost nothing. With
 * {@code virtualThreads(false)} every connection gets a platform thread.</p>
 *
 * <p>It is strict about what it accepts, because lenient parsing is where
 * request smuggling and resource exhaustion come from:</p>
 * <ul>
 *   <li>bounded request head ({@code 431}), header count, body
 *       ({@code 413}) and connection count;</li>
 *   <li>a deadline for receiving the request head (slow-header attacks) and
 *       idle/read timeouts;</li>
 *   <li>ambiguous framing is refused: {@code Content-Length} together with
 *       {@code Transfer-Encoding}, conflicting or malformed lengths, any
 *       transfer coding other than {@code chunked}, folded header lines,
 *       whitespace before the colon, control characters, a missing or
 *       repeated {@code Host};</li>
 *   <li>response headers are validated, so application data can never split
 *       a response.</li>
 * </ul>
 *
 * <p>Use it by depending on {@code ligero-server-loom} instead of another
 * engine; application code does not change.</p>
 */
public final class LoomServerEngine implements ServerEngine {

    private static final Logger log = LoggerFactory.getLogger(LoomServerEngine.class);

    /**
     * Protective limits. The defaults suit an application server; tests and
     * unusual deployments can tighten or relax them.
     *
     * @param maxHeaderBytes  largest accepted request line + headers
     * @param maxHeaders      largest accepted number of header lines
     * @param headerTimeout   time allowed to receive a complete request head
     * @param idleTimeout     how long a keep-alive connection may sit idle
     * @param readTimeout     longest wait for more request-body bytes
     * @param maxConnections  concurrent connections; further ones are refused
     * @param maxWsMessageBytes largest accepted WebSocket message
     * @param dedicatedThreads how many concurrent connections are served on a
     *                         dedicated platform thread before falling back to
     *                         virtual threads; 0 means virtual threads only
     */
    public record Limits(int maxHeaderBytes, int maxHeaders, Duration headerTimeout, Duration idleTimeout,
                         Duration readTimeout, int maxConnections, int maxWsMessageBytes, int dedicatedThreads) {

        public Limits {
            if (maxHeaderBytes < 512 || maxHeaders < 1 || maxConnections < 1 || maxWsMessageBytes < 125
                    || dedicatedThreads < 0) {
                throw new IllegalArgumentException("limits are too small to serve any request");
            }
        }

        public static Limits defaults() {
            return new Limits(16 * 1024, 100, Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ofSeconds(30), 20_000, 1024 * 1024, 256);
        }

        /** A copy with a different number of dedicated platform threads. */
        public Limits withDedicatedThreads(int dedicatedThreads) {
            return new Limits(maxHeaderBytes, maxHeaders, headerTimeout, idleTimeout, readTimeout,
                maxConnections, maxWsMessageBytes, dedicatedThreads);
        }
    }

    private final Limits limits;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger dedicatedInUse =
        new java.util.concurrent.atomic.AtomicInteger();
    private ServerSocket serverSocket;
    private ExecutorService platformThreads;
    private ExecutorService virtualThreads;
    private Thread acceptor;
    private volatile boolean stopping;

    public LoomServerEngine() {
        this(Limits.defaults());
    }

    public LoomServerEngine(Limits limits) {
        this.limits = limits;
    }

    @Override
    public void start(EngineConfig config, HttpHandler rootHandler) throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("Engine already started");
        }
        stopping = false;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.host(), config.port()), 1024);
        serverSocket = socket;
        dedicatedInUse.set(0);
        platformThreads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ligero-connection");
            thread.setDaemon(true);
            return thread;
        });
        virtualThreads = config.virtualThreads() ? Executors.newVirtualThreadPerTaskExecutor() : null;
        acceptor = Thread.ofPlatform().name("ligero-acceptor").start(() -> acceptLoop(socket, config, rootHandler));
        log.debug("Loom server engine listening on {}:{}", config.host(), port());
    }

    private void acceptLoop(ServerSocket socket, EngineConfig config, HttpHandler rootHandler) {
        while (!stopping) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (!stopping) {
                    log.warn("Accept failed", e);
                }
                continue;
            }
            if (connections.size() >= limits.maxConnections()) {
                closeQuietly(client); // shed load instead of queueing without bound
                continue;
            }
            // Dedicated platform threads first (fastest wake-up); virtual threads
            // once those are taken, so connection count is not bounded by OS threads.
            boolean dedicated = virtualThreads == null || claimDedicatedThread();
            Connection connection = new Connection(this, client, config, rootHandler, limits, dedicated);
            connections.add(connection);
            try {
                (dedicated ? platformThreads : virtualThreads).execute(connection);
            } catch (RuntimeException rejected) {
                connectionClosed(connection);
                closeQuietly(client);
            }
        }
    }

    @Override
    public void stop(Duration grace) {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            return;
        }
        stopping = true;
        closeQuietly(socket);
        // connections waiting for their next request have nothing to finish
        connections.forEach(Connection::closeIfIdle);
        long deadline = System.nanoTime() + grace.toNanos();
        try {
            while (!connections.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
                connections.forEach(Connection::closeIfIdle);
            }
            acceptor.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connections.forEach(Connection::forceClose);
        platformThreads.shutdownNow();
        if (virtualThreads != null) {
            virtualThreads.shutdownNow();
        }
        connections.clear();
        serverSocket = null;
        platformThreads = null;
        virtualThreads = null;
        acceptor = null;
    }

    @Override
    public int port() {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            throw new IllegalStateException("Engine is not running");
        }
        return socket.getLocalPort();
    }

    boolean isStopping() {
        return stopping;
    }

    private boolean claimDedicatedThread() {
        int limit = limits.dedicatedThreads();
        while (true) {
            int current = dedicatedInUse.get();
            if (current >= limit) {
                return false;
            }
            if (dedicatedInUse.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void connectionClosed(Connection connection) {
        if (connections.remove(connection) && connection.dedicated() && virtualThreads != null) {
            dedicatedInUse.decrementAndGet();
        }
    }

    /** Open connections (diagnostics, tests). */
    int openConnections() {
        return connections.size();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
