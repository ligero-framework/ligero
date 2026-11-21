package com.ligero.amqp;

import com.ligero.Ligero;
import com.ligero.spi.BodyMapper;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.Delivery;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.ReceiverOptions;
import org.apache.qpid.protonj2.client.Sender;
import org.apache.qpid.protonj2.client.exceptions.ClientException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Sends and consumes JSON messages over AMQP 1.0, the standard messaging
 * protocol spoken by RabbitMQ 4, ActiveMQ Artemis, Azure Service Bus,
 * Solace and others, through the Apache Qpid ProtonJ2 client.
 *
 * <pre>{@code
 * Amqp amqp = Amqp.builder().host("broker").port(5671).tls(true)
 *     .user("shop").password(System.getenv("AMQP_PASSWORD"))
 *     .build();
 *
 * amqp.listen("orders", OrderPlaced.class, order -> shipping.prepare(order));
 * amqp.attach(app);
 *
 * amqp.send("orders", new OrderPlaced(42, "ana@example.com"));
 * }</pre>
 *
 * <p>Delivery is at-least-once: a message is accepted only after its handler
 * returns. When the handler throws, the message is returned to the broker
 * as {@code modified} with delivery-failed set, so the broker redelivers it
 * and, past its limit, dead-letters it. A message whose body cannot be read
 * as the expected type is {@code rejected} instead, since retrying cannot
 * help. Handlers should be idempotent.</p>
 */
public final class Amqp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Amqp.class);

    private final Builder config;
    private final BodyMapper mapper;
    private final Client client = Client.create();
    private final Map<String, Sender> senders = new ConcurrentHashMap<>();
    private final List<Listener<?>> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Connection sendConnection;

    private Amqp(Builder config) {
        this.config = config;
        this.mapper = config.mapper != null ? config.mapper
            : ServiceLoader.load(BodyMapper.class).findFirst().orElse(null);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- sending

    /** Sends {@code payload} (an object as JSON, a {@code String} as UTF-8 text) to {@code address}. */
    public void send(String address, Object payload) {
        send(address, payload, Map.of());
    }

    /** Same, with application properties (strings, numbers, booleans). */
    public void send(String address, Object payload, Map<String, ?> properties) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(payload, "payload");
        boolean text = payload instanceof String;
        String body = text ? (String) payload : json(payload);
        try {
            Message<String> message = Message.create(body)
                .contentType(text ? "text/plain; charset=utf-8" : "application/json");
            for (Map.Entry<String, ?> property : properties.entrySet()) {
                message.property(property.getKey(), property.getValue());
            }
            sender(address).send(message).awaitSettlement(config.sendTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ClientException e) {
            senders.remove(address);
            throw new MessagingException("Could not send to " + address, e);
        }
    }

    private Sender sender(String address) throws ClientException {
        Sender existing = senders.get(address);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (sendConnection == null) {
                sendConnection = connect();
            }
            Sender created = sendConnection.openSender(address);
            senders.put(address, created);
            return created;
        }
    }

    private String json(Object payload) {
        if (mapper == null) {
            throw new IllegalStateException("No BodyMapper to write JSON. Add ligero-json or pass one to the builder");
        }
        return mapper.writeJson(payload);
    }

    private Connection connect() throws ClientException {
        ConnectionOptions options = new ConnectionOptions()
            .sslEnabled(config.tls)
            .reconnectEnabled(true);
        if (config.user != null) {
            options.user(config.user).password(config.password);
        }
        return client.connect(config.host, config.port, options);
    }

    // ---------------------------------------------------------------- consuming

    /** Consumes {@code address} on a virtual thread. */
    public synchronized <T> Amqp listen(String address, Class<T> type, Consumer<T> handler) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (type != String.class && mapper == null) {
            throw new IllegalStateException("No BodyMapper to read JSON. Add ligero-json or pass one to the builder");
        }
        Listener<T> listener = new Listener<>(address, type, handler);
        listeners.add(listener);
        if (running.get()) {
            listener.start();
        }
        return this;
    }

    /** Starts the listeners when {@code app} starts and closes everything when it stops. */
    public Amqp attach(Ligero app) {
        app.onStart(this::start).onStop(this::close);
        return this;
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            listeners.forEach(Listener::start);
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        listeners.forEach(Listener::stop);
        senders.clear();
        if (sendConnection != null) {
            sendConnection.close();
            sendConnection = null;
        }
        client.close();
    }

    private final class Listener<T> {
        private final String address;
        private final Class<T> type;
        private final Consumer<T> handler;
        private volatile boolean active;
        private volatile Connection connection;
        private Thread thread;

        Listener(String address, Class<T> type, Consumer<T> handler) {
            this.address = address;
            this.type = type;
            this.handler = handler;
        }

        void start() {
            active = true;
            thread = Thread.ofVirtual().name("ligero-amqp-" + address).start(this::loop);
        }

        void stop() {
            active = false;
            if (thread != null) {
                try {
                    thread.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Connection current = connection;
            if (current != null) {
                current.close();
            }
        }

        private void loop() {
            long backoff = 100;
            while (active) {
                try {
                    if (connection == null) {
                        connection = connect();
                    }
                    Receiver receiver = connection.openReceiver(address,
                        new ReceiverOptions().autoAccept(false).creditWindow(config.prefetch));
                    backoff = 100;
                    while (active) {
                        Delivery delivery = receiver.receive(500, TimeUnit.MILLISECONDS);
                        if (delivery != null) {
                            handle(delivery);
                        }
                    }
                    receiver.close();
                } catch (ClientException | RuntimeException e) {
                    if (!active) {
                        return;
                    }
                    log.warn("AMQP listener on {} failed; retrying in {} ms: {}", address, backoff, e.toString());
                    Connection broken = connection;
                    connection = null;
                    if (broken != null) {
                        broken.close();
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
        }

        private void handle(Delivery delivery) throws ClientException {
            T payload;
            try {
                payload = decode(delivery.message());
            } catch (RuntimeException | ClientException unreadable) {
                log.error("Rejecting unreadable message on {}: {}", address, unreadable.toString());
                delivery.reject("amqp:decode-error", "Not a valid " + type.getSimpleName());
                return;
            }
            try {
                handler.accept(payload);
                delivery.accept();
            } catch (RuntimeException failure) {
                log.warn("Handler for {} failed; the message goes back to the broker", address, failure);
                delivery.modified(true, false);
            }
        }

        private T decode(Message<Object> message) throws ClientException {
            Object body = message.body();
            String text;
            if (body instanceof String string) {
                text = string;
            } else if (body instanceof byte[] bytes) {
                text = new String(bytes, StandardCharsets.UTF_8);
            } else {
                throw new MessagingException("Only text or binary JSON bodies are read, got "
                    + (body == null ? "no body" : body.getClass().getSimpleName()));
            }
            if (type == String.class) {
                return type.cast(text);
            }
            return mapper.readJson(text, type);
        }
    }

    /** Connection settings; {@code localhost:5672}, no TLS, anonymous by default. */
    public static final class Builder {
        private String host = "localhost";
        private int port = 5672;
        private boolean tls;
        private String user;
        private String password;
        private BodyMapper mapper;
        private int prefetch = 10;
        private long sendTimeoutMillis = 30_000;

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        public Builder port(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            this.port = port;
            return this;
        }

        /** Connects over TLS (AMQPS, usually port 5671). */
        public Builder tls(boolean tls) {
            this.tls = tls;
            return this;
        }

        public Builder user(String user) {
            this.user = Objects.requireNonNull(user, "user");
            return this;
        }

        public Builder password(String password) {
            this.password = Objects.requireNonNull(password, "password");
            return this;
        }

        public Builder bodyMapper(BodyMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            return this;
        }

        /** Messages each listener may hold unacknowledged (default 10). */
        public Builder prefetch(int prefetch) {
            if (prefetch < 1) {
                throw new IllegalArgumentException("prefetch must be >= 1");
            }
            this.prefetch = prefetch;
            return this;
        }

        /** How long {@code send} waits for the broker to settle a message (default 30 s). */
        public Builder sendTimeout(java.time.Duration timeout) {
            this.sendTimeoutMillis = timeout.toMillis();
            return this;
        }

        public Amqp build() {
            return new Amqp(this);
        }
    }
}
