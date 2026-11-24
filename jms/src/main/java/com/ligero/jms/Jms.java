package com.ligero.jms;

import com.ligero.Ligero;
import com.ligero.spi.BodyMapper;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Sends and consumes messages over any Jakarta Messaging (JMS 3) broker —
 * ActiveMQ Artemis, IBM MQ, Amazon MQ, ... — with JSON payloads.
 *
 * <pre>{@code
 * Jms jms = Jms.of(new ActiveMQConnectionFactory("tcp://broker:61616", user, password));
 *
 * jms.listen("orders", OrderPlaced.class, order -> shipping.prepare(order));
 * jms.attach(app);                                 // listeners start and stop with the app
 *
 * jms.send("orders", new OrderPlaced(42, "ana@example.com"));
 * }</pre>
 *
 * <p>Delivery is at-least-once: each message is received in a transacted
 * session that is committed only after the handler returns. When the
 * handler throws, the session rolls back and the broker redelivers the
 * message (and, after its redelivery limit, moves it to its dead-letter
 * queue). Handlers should therefore be idempotent.</p>
 *
 * <p>Payloads are JSON text messages, read into the type the listener asks
 * for. {@code ObjectMessage} — Java serialization, a well-known path to
 * remote code execution — is never read: such messages are rejected and
 * logged.</p>
 */
public final class Jms implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Jms.class);

    /** Message property holding the payload's Java type name, for diagnostics only. */
    public static final String TYPE_PROPERTY = "ligeroType";

    private final ConnectionFactory factory;
    private final BodyMapper mapper;
    private final List<Listener<?>> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Connection sendConnection;

    private Jms(ConnectionFactory factory, BodyMapper mapper) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.mapper = mapper;
    }

    /** Uses {@code factory} and the JSON mapper on the classpath ({@code ligero-json}). */
    public static Jms of(ConnectionFactory factory) {
        return new Jms(factory, ServiceLoader.load(BodyMapper.class).findFirst().orElse(null));
    }

    /** Uses {@code factory} and an explicit JSON mapper. */
    public static Jms of(ConnectionFactory factory, BodyMapper mapper) {
        return new Jms(factory, Objects.requireNonNull(mapper, "mapper"));
    }

    // ---------------------------------------------------------------- sending

    /** Sends {@code payload} (an object as JSON, a {@code String} as is) to queue {@code queue}. */
    public void send(String queue, Object payload) {
        send(queue, payload, Map.of());
    }

    /** Same, with message properties (strings, numbers, booleans). */
    public void send(String queue, Object payload, Map<String, ?> properties) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(payload, "payload");
        try {
            Connection connection = sendConnection();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageProducer producer = session.createProducer(session.createQueue(queue))) {
                TextMessage message = session.createTextMessage(encode(payload));
                message.setStringProperty(TYPE_PROPERTY, payload.getClass().getName());
                for (Map.Entry<String, ?> property : properties.entrySet()) {
                    message.setObjectProperty(property.getKey(), property.getValue());
                }
                producer.send(message);
            }
        } catch (JMSException e) {
            throw new MessagingException("Could not send to " + queue, e);
        }
    }

    private synchronized Connection sendConnection() throws JMSException {
        if (sendConnection == null) {
            sendConnection = factory.createConnection();
            sendConnection.setExceptionListener(e -> {
                log.warn("JMS connection used for sending failed; it will be reopened: {}", e.toString());
                resetSendConnection();
            });
        }
        return sendConnection;
    }

    private synchronized void resetSendConnection() {
        closeQuietly(sendConnection);
        sendConnection = null;
    }

    private String encode(Object payload) {
        if (payload instanceof String text) {
            return text;
        }
        if (mapper == null) {
            throw new IllegalStateException("No BodyMapper to write JSON. Add ligero-json or pass one to Jms.of");
        }
        return mapper.writeJson(payload);
    }

    // ---------------------------------------------------------------- consuming

    /** Consumes queue {@code queue} with one consumer. */
    public <T> Jms listen(String queue, Class<T> type, Consumer<T> handler) {
        return listen(queue, type, 1, handler);
    }

    /**
     * Consumes queue {@code queue} with {@code concurrency} consumers, each on
     * its own virtual thread. Listeners added after {@link #start()} start
     * immediately.
     */
    public synchronized <T> Jms listen(String queue, Class<T> type, int concurrency, Consumer<T> handler) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1");
        }
        if (type != String.class && mapper == null) {
            throw new IllegalStateException("No BodyMapper to read JSON. Add ligero-json or pass one to Jms.of");
        }
        Listener<T> listener = new Listener<>(queue, type, concurrency, handler);
        listeners.add(listener);
        if (running.get()) {
            listener.start();
        }
        return this;
    }

    /** Starts the listeners when {@code app} starts and closes everything when it stops. */
    public Jms attach(Ligero app) {
        app.onStart(this::start).onStop(this::close);
        return this;
    }

    /** Starts every listener. */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            listeners.forEach(Listener::start);
        }
    }

    /** Stops the listeners (letting a message being handled finish) and closes the connections. */
    @Override
    public synchronized void close() {
        running.set(false);
        listeners.forEach(Listener::stop);
        resetSendConnection();
    }

    private final class Listener<T> {
        private final String queue;
        private final Class<T> type;
        private final int concurrency;
        private final Consumer<T> handler;
        private final List<Thread> threads = new ArrayList<>();
        private volatile Connection connection;
        private volatile boolean active;

        Listener(String queue, Class<T> type, int concurrency, Consumer<T> handler) {
            this.queue = queue;
            this.type = type;
            this.concurrency = concurrency;
            this.handler = handler;
        }

        void start() {
            active = true;
            for (int i = 0; i < concurrency; i++) {
                threads.add(Thread.ofVirtual().name("ligero-jms-" + queue + "-" + i).start(this::loop));
            }
        }

        void stop() {
            active = false;
            closeQuietly(connection);
            for (Thread thread : threads) {
                try {
                    thread.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            threads.clear();
        }

        private void loop() {
            long backoff = 100;
            while (active) {
                try {
                    Connection current = sharedConnection();
                    try (Session session = current.createSession(true, Session.SESSION_TRANSACTED);
                         MessageConsumer consumer = session.createConsumer(destination(session))) {
                        backoff = 100;
                        while (active) {
                            Message message = consumer.receive(1_000);
                            if (message != null) {
                                handle(session, message);
                            }
                        }
                    }
                } catch (JMSException | RuntimeException e) {
                    if (!active) {
                        return;
                    }
                    log.warn("JMS listener on {} lost its connection; retrying in {} ms: {}", queue, backoff, e.toString());
                    resetConnection();
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
        }

        private Destination destination(Session session) throws JMSException {
            return session.createQueue(queue);
        }

        private void handle(Session session, Message message) throws JMSException {
            T payload;
            try {
                payload = decode(message);
            } catch (RuntimeException | JMSException unreadable) {
                // a message that can never be read would be redelivered forever: drop it, loudly
                log.error("Discarding unreadable message {} on {}: {}", message.getJMSMessageID(), queue,
                    unreadable.toString());
                session.commit();
                return;
            }
            try {
                handler.accept(payload);
                session.commit();
            } catch (RuntimeException failure) {
                log.warn("Handler for {} failed on message {}; it will be redelivered", queue,
                    message.getJMSMessageID(), failure);
                session.rollback();
            }
        }

        private T decode(Message message) throws JMSException {
            if (!(message instanceof TextMessage text)) {
                throw new MessagingException("Only text (JSON) messages are read, got "
                    + message.getClass().getSimpleName());
            }
            String body = text.getText();
            if (type == String.class) {
                return type.cast(body);
            }
            return mapper.readJson(body, type);
        }

        private synchronized Connection sharedConnection() throws JMSException {
            if (connection == null) {
                Connection created = factory.createConnection();
                created.start();
                connection = created;
            }
            return connection;
        }

        private synchronized void resetConnection() {
            closeQuietly(connection);
            connection = null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException | RuntimeException ignored) {
                // closing anyway
            }
        }
    }
}
