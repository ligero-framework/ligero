import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-dependency HTTP/1.1 load driver for the framework benchmark.
 *
 * <p>Usage: {@code java Load.java <port> <connections> <seconds> <scenario>}
 * where scenario is {@code list} (GET /products), {@code byId}
 * (GET /products/{id}) or {@code create} (POST /products with a JSON body).
 * Prints one line:
 * {@code scenario=<s> requests=<n> throughput=<req/s> p50=<ms> p99=<ms> errors=<n>}</p>
 *
 * <p>Each connection is a keep-alive socket driven by its own virtual thread
 * that writes a pre-built request and reads the full response (Content-Length
 * or chunked). Talking raw HTTP keeps the client far cheaper than the servers
 * under test, so the numbers reflect the server rather than the driver.</p>
 */
public final class Load {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int connections = Integer.parseInt(args[1]);
        long durationNanos = Long.parseLong(args[2]) * 1_000_000_000L;
        String scenario = args[3];
        byte[] request = request(scenario, port);
        int expectedStatus = "create".equals(scenario) ? 201 : 200;

        AtomicLong errors = new AtomicLong();
        List<Worker> workers = new ArrayList<>();
        long deadline = System.nanoTime() + durationNanos;
        for (int i = 0; i < connections; i++) {
            Worker worker = new Worker(port, request, expectedStatus, deadline, errors);
            workers.add(worker);
            worker.thread = Thread.ofVirtual().start(worker);
        }
        long total = 0;
        for (Worker worker : workers) {
            worker.thread.join();
            total += worker.count;
        }
        long[] all = new long[(int) Math.min(total, Integer.MAX_VALUE - 8)];
        int at = 0;
        for (Worker worker : workers) {
            int n = Math.min(worker.count, all.length - at);
            System.arraycopy(worker.latencies, 0, all, at, n);
            at += n;
        }
        Arrays.sort(all);
        double seconds = durationNanos / 1e9;
        System.out.printf("scenario=%s requests=%d throughput=%d p50=%.3f p99=%.3f errors=%d%n",
            scenario, total, Math.round(total / seconds),
            percentile(all, 0.50) / 1e6, percentile(all, 0.99) / 1e6, errors.get());
    }

    private static byte[] request(String scenario, int port) {
        String host = "Host: 127.0.0.1:" + port + "\r\n";
        String text = switch (scenario) {
            case "list" -> "GET /products HTTP/1.1\r\n" + host + "Accept: application/json\r\n\r\n";
            case "byId" -> "GET /products/2 HTTP/1.1\r\n" + host + "Accept: application/json\r\n\r\n";
            case "create" -> {
                String body = "{\"name\":\"Auriculares\",\"price\":49.5}";
                yield "POST /products HTTP/1.1\r\n" + host + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length() + "\r\n\r\n" + body;
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
    }

    private static final class Worker implements Runnable {
        private final int port;
        private final byte[] request;
        private final int expectedStatus;
        private final long deadline;
        private final AtomicLong errors;
        long[] latencies = new long[1 << 16];
        int count;
        Thread thread;

        Worker(int port, byte[] request, int expectedStatus, long deadline, AtomicLong errors) {
            this.port = port;
            this.request = request;
            this.expectedStatus = expectedStatus;
            this.deadline = deadline;
            this.errors = errors;
        }

        @Override
        public void run() {
            while (System.nanoTime() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(10_000);
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                    OutputStream out = socket.getOutputStream();
                    InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
                    while (System.nanoTime() < deadline) {
                        long start = System.nanoTime();
                        out.write(request);
                        out.flush();
                        Response response = readResponse(in);
                        long elapsed = System.nanoTime() - start;
                        if (response.status != expectedStatus) {
                            errors.incrementAndGet();
                        } else {
                            if (count == latencies.length) {
                                latencies = Arrays.copyOf(latencies, latencies.length * 2);
                            }
                            latencies[count++] = elapsed;
                        }
                        if (response.close) {
                            break; // server asked to close: reconnect
                        }
                    }
                } catch (IOException e) {
                    errors.incrementAndGet();
                }
            }
        }

        private record Response(int status, boolean close) {
        }

        /** Reads one full response: status line, headers, then a Content-Length or chunked body. */
        private static Response readResponse(InputStream in) throws IOException {
            String statusLine = readLine(in);
            int status = Integer.parseInt(statusLine.substring(9, 12));
            long contentLength = -1;
            boolean chunked = false;
            boolean close = false;
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon);
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    contentLength = Long.parseLong(value);
                } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                    chunked = value.toLowerCase().contains("chunked");
                } else if (name.equalsIgnoreCase("Connection")) {
                    close = value.equalsIgnoreCase("close");
                }
            }
            if (chunked) {
                while (true) {
                    String sizeLine = readLine(in);
                    int semicolon = sizeLine.indexOf(';');
                    int size = Integer.parseInt(semicolon < 0 ? sizeLine.trim() : sizeLine.substring(0, semicolon).trim(), 16);
                    if (size == 0) {
                        while (!readLine(in).isEmpty()) {
                            // trailers
                        }
                        break;
                    }
                    skip(in, size);
                    readLine(in); // CRLF after the chunk
                }
            } else if (contentLength > 0) {
                skip(in, contentLength);
            }
            return new Response(status, close);
        }

        private static void skip(InputStream in, long bytes) throws IOException {
            long remaining = bytes;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new EOFException();
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder(64);
            for (int b = in.read(); b != '\n'; b = in.read()) {
                if (b < 0) {
                    throw new EOFException();
                }
                if (b != '\r') {
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }
    }
}
