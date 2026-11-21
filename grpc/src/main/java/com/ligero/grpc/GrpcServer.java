package com.ligero.grpc;

import com.ligero.Ligero;

import io.grpc.BindableService;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsServerCredentials;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Serves gRPC services (<a href="https://grpc.io/docs/languages/java/">grpc-java</a>)
 * on their own port, next to the HTTP server, with the same lifecycle:
 *
 * <pre>{@code
 * GrpcServer grpc = GrpcServer.builder(9090)
 *     .service(new GreeterService())        // your generated *ImplBase subclass
 *     .health()                             // grpc.health.v1, for load balancers and Kubernetes
 *     .build();
 * grpc.attach(app);                         // starts and stops with the app
 * app.start();
 * }</pre>
 *
 * <p>Each call runs on its own virtual thread, so service code can block
 * (JDBC, other services) as in HTTP handlers. Limits are on by default:
 * 4 MiB messages, 8 KiB of metadata, 100 concurrent streams per connection,
 * a 20 s TLS/HTTP/2 handshake and idle connections closed after 5 minutes;
 * clients may not ping more often than every 30 s. An
 * {@link com.ligero.http.HttpException} thrown by a service becomes the
 * matching gRPC status with its message (404 → {@code NOT_FOUND}, 403 →
 * {@code PERMISSION_DENIED}, ...); any other exception becomes
 * {@code UNKNOWN} with no detail, and is logged.</p>
 */
public final class GrpcServer {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final Builder config;
    private final HealthStatusManager health;
    private ExecutorService executor;
    private Server server;

    private GrpcServer(Builder config) {
        this.config = config;
        this.health = config.health ? new HealthStatusManager() : null;
    }

    /** A server on {@code port} (0 picks a free one). */
    public static Builder builder(int port) {
        return new Builder(port);
    }

    /** Starts this server when {@code app} starts and stops it, gracefully, when the app stops. */
    public GrpcServer attach(Ligero app) {
        app.onStart(this::start).onStop(this::stop);
        return this;
    }

    /** Binds the port and starts serving. */
    public synchronized void start() {
        if (server != null) {
            throw new IllegalStateException("The gRPC server is already running");
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        NettyServerBuilder builder = NettyServerBuilder
            .forAddress(new InetSocketAddress(config.host, config.port), config.credentials)
            .executor(executor)
            .maxInboundMessageSize(config.maxMessageBytes)
            .maxInboundMetadataSize(config.maxMetadataBytes)
            .maxConcurrentCallsPerConnection(config.maxConcurrentCallsPerConnection)
            .handshakeTimeout(config.handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .maxConnectionIdle(config.maxConnectionIdle.toMillis(), TimeUnit.MILLISECONDS)
            .permitKeepAliveTime(30, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(false);
        List<ServerInterceptor> interceptors = new ArrayList<>(config.interceptors);
        interceptors.add(new HttpExceptionInterceptor()); // innermost: sees the service's own exceptions
        for (ServerServiceDefinition service : config.services) {
            builder.addService(ServerInterceptors.intercept(service, interceptors));
        }
        if (health != null) {
            builder.addService(health.getHealthService());
            for (ServerServiceDefinition service : config.services) {
                health.setStatus(service.getServiceDescriptor().getName(), HealthCheckResponse.ServingStatus.SERVING);
            }
        }
        if (config.reflection) {
            builder.addService(ProtoReflectionServiceV1.newInstance());
        }
        try {
            server = builder.build().start();
        } catch (IOException e) {
            executor.shutdownNow();
            throw new UncheckedIOException("Could not start the gRPC server on port " + config.port, e);
        }
        log.info("gRPC server listening on {}:{} ({} service(s))", config.host, server.getPort(), config.services.size());
    }

    /**
     * Stops accepting calls, lets running ones finish within the grace period
     * (default 10 s), then cancels the rest.
     */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        if (health != null) {
            health.enterTerminalState(); // health checks report NOT_SERVING while draining
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(config.shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                server.shutdownNow();
                server.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
            server = null;
        }
    }

    /** The bound port; only while running. */
    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("The gRPC server is not running");
        }
        return server.getPort();
    }

    /**
     * Health status of one service (or of the whole server with {@code ""}),
     * when {@link Builder#health()} is on — e.g. NOT_SERVING while a
     * dependency is down.
     */
    public void setHealth(String service, boolean serving) {
        if (health == null) {
            throw new IllegalStateException("Turn health checks on with GrpcServer.builder(...).health()");
        }
        health.setStatus(service, serving
            ? HealthCheckResponse.ServingStatus.SERVING : HealthCheckResponse.ServingStatus.NOT_SERVING);
    }

    /** Builder with safe limits. */
    public static final class Builder {
        private final int port;
        private String host = "0.0.0.0";
        private final List<ServerServiceDefinition> services = new ArrayList<>();
        private final List<ServerInterceptor> interceptors = new ArrayList<>();
        private ServerCredentials credentials = InsecureServerCredentials.create();
        private boolean health;
        private boolean reflection;
        private int maxMessageBytes = 4 * 1024 * 1024;
        private int maxMetadataBytes = 8 * 1024;
        private int maxConcurrentCallsPerConnection = 100;
        private Duration handshakeTimeout = Duration.ofSeconds(20);
        private Duration maxConnectionIdle = Duration.ofMinutes(5);
        private Duration shutdownGrace = Duration.ofSeconds(10);

        private Builder(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            this.port = port;
        }

        /** Address to bind (default all interfaces). */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /** A service: usually an instance of a class generated by protoc ({@code *ImplBase}). */
        public Builder service(BindableService service) {
            services.add(service.bindService());
            return this;
        }

        /** A service defined by hand (or wrapped with its own interceptors). */
        public Builder service(ServerServiceDefinition service) {
            services.add(Objects.requireNonNull(service, "service"));
            return this;
        }

        /** Runs around every call of every service, in the order added (authentication, logging, ...). */
        public Builder interceptor(ServerInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /** Serves the standard {@code grpc.health.v1.Health} service. */
        public Builder health() {
            this.health = true;
            return this;
        }

        /**
         * Serves server reflection, which lets tools such as grpcurl list and
         * call the services without the .proto files. Handy in development;
         * it also tells anyone who can connect what the API looks like.
         */
        public Builder reflection() {
            this.reflection = true;
            return this;
        }

        /** TLS with a PEM certificate chain and an unencrypted PKCS#8 private key. */
        public Builder tls(File certificateChain, File privateKey) {
            try {
                this.credentials = TlsServerCredentials.create(certificateChain, privateKey);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the TLS certificate or key", e);
            }
            return this;
        }

        /** Any grpc-java server credentials (mutual TLS, ...). */
        public Builder credentials(ServerCredentials credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        public Builder maxMessageBytes(int maxMessageBytes) {
            this.maxMessageBytes = positive(maxMessageBytes, "maxMessageBytes");
            return this;
        }

        public Builder maxMetadataBytes(int maxMetadataBytes) {
            this.maxMetadataBytes = positive(maxMetadataBytes, "maxMetadataBytes");
            return this;
        }

        public Builder maxConcurrentCallsPerConnection(int calls) {
            this.maxConcurrentCallsPerConnection = positive(calls, "maxConcurrentCallsPerConnection");
            return this;
        }

        public Builder handshakeTimeout(Duration timeout) {
            this.handshakeTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder maxConnectionIdle(Duration idle) {
            this.maxConnectionIdle = Objects.requireNonNull(idle, "idle");
            return this;
        }

        public Builder shutdownGrace(Duration grace) {
            this.shutdownGrace = Objects.requireNonNull(grace, "grace");
            return this;
        }

        public GrpcServer build() {
            if (services.isEmpty()) {
                throw new IllegalStateException("Add at least one service");
            }
            return new GrpcServer(this);
        }

        private static int positive(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be >= 1");
            }
            return value;
        }
    }
}
