package com.ligero.kafka;

import com.ligero.Ligero;
import com.ligero.spi.BodyMapper;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Produces and consumes Kafka records with JSON values.
 *
 * <pre>{@code
 * Kafka kafka = Kafka.builder()
 *     .bootstrapServers("kafka-1:9092,kafka-2:9092")
 *     .groupId("shipping")
 *     .build();
 *
 * kafka.listen("orders", OrderPlaced.class, order -> shipping.prepare(order));
 * kafka.attach(app);
 *
 * kafka.send("orders", String.valueOf(order.id()), order);   // the key keeps one order's events in order
 * }</pre>
 *
 * <p>Delivery is at-least-once. Offsets are committed only after the records
 * of a poll have been handled. A handler that throws is retried
 * ({@link Builder#retries}, with a growing pause); when it still fails, the
 * record is copied to the dead-letter topic {@code <topic>.DLT} — with
 * headers saying where it came from and why it failed — and consumption
 * moves on, so one bad record cannot stop a partition. Records whose value
 * cannot be read as the expected type go to the dead-letter topic at once.
 * Handlers should be idempotent.</p>
 *
 * <p>Producer defaults favour safety: {@code acks=all} and idempotence, so a
 * retried send cannot duplicate or reorder records. Anything else — TLS,
 * SASL, compression — is a {@link Builder#property(String, String)} away,
 * with the standard Kafka client names.</p>
 */
public final class Kafka implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Kafka.class);

    /** Dead-letter header: where the record came from ({@code topic-partition@offset}). */
    public static final String ORIGIN_HEADER = "ligero.dlt.origin";
    /** Dead-letter header: the exception that made the record fail. */
    public static final String ERROR_HEADER = "ligero.dlt.error";

    private final Builder config;
    private final BodyMapper mapper;
    private final List<Listener<?>> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private KafkaProducer<String, String> producer;

    private Kafka(Builder config) {
        this.config = config;
        this.mapper = config.mapper != null ? config.mapper
            : ServiceLoader.load(BodyMapper.class).findFirst().orElse(null);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- producing

    /** Sends {@code value} (an object as JSON, a {@code String} as is) without a key. */
    public CompletableFuture<RecordMetadata> send(String topic, Object value) {
        return send(topic, null, value);
    }

    /**
     * Sends {@code value} with {@code key}; records with the same key go to
     * the same partition, in order. Completes when the brokers acknowledged it.
     */
    public CompletableFuture<RecordMetadata> send(String topic, String key, Object value) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(value, "value");
        String encoded = value instanceof String text ? text : json(value);
        CompletableFuture<RecordMetadata> result = new CompletableFuture<>();
        producer().send(new ProducerRecord<>(topic, key, encoded), (metadata, error) -> {
            if (error != null) {
                result.completeExceptionally(new MessagingException("Could not send to " + topic, error));
            } else {
                result.complete(metadata);
            }
        });
        return result;
    }

    private synchronized KafkaProducer<String, String> producer() {
        if (producer == null) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers);
            properties.put(ProducerConfig.ACKS_CONFIG, "all");
            properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            properties.put(ProducerConfig.LINGER_MS_CONFIG, 5);
            properties.putAll(config.properties);
            producer = new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());
        }
        return producer;
    }

    private String json(Object value) {
        if (mapper == null) {
            throw new IllegalStateException("No BodyMapper to write JSON. Add ligero-json or pass one to the builder");
        }
        return mapper.writeJson(value);
    }

    // ---------------------------------------------------------------- consuming

    /** Consumes {@code topic}, handing each value to {@code handler}. */
    public <T> Kafka listen(String topic, Class<T> type, Consumer<T> handler) {
        return listenToRecords(topic, type, record -> handler.accept(record.value()));
    }

    /** Consumes {@code topic}, with the key, headers, partition and offset of each record. */
    public synchronized <T> Kafka listenToRecords(String topic, Class<T> type, Consumer<KafkaRecord<T>> handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (config.groupId == null) {
            throw new IllegalStateException("Consumers need a group: Kafka.builder().groupId(\"my-service\")");
        }
        if (type != String.class && mapper == null) {
            throw new IllegalStateException("No BodyMapper to read JSON. Add ligero-json or pass one to the builder");
        }
        Listener<T> listener = new Listener<>(topic, type, handler);
        listeners.add(listener);
        if (running.get()) {
            listener.start();
        }
        return this;
    }

    /** Starts consuming when {@code app} starts and closes everything when it stops. */
    public Kafka attach(Ligero app) {
        app.onStart(this::start).onStop(this::close);
        return this;
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            listeners.forEach(Listener::start);
        }
    }

    /** Stops the consumers (committing what was handled) and flushes the producer. */
    @Override
    public synchronized void close() {
        running.set(false);
        listeners.forEach(Listener::stop);
        if (producer != null) {
            producer.close(Duration.ofSeconds(10));
            producer = null;
        }
    }

    /** One consumed record. */
    public record KafkaRecord<T>(String topic, int partition, long offset, String key, T value,
                                 Map<String, String> headers) {
    }

    private final class Listener<T> {
        private final String topic;
        private final Class<T> type;
        private final Consumer<KafkaRecord<T>> handler;
        private volatile boolean active;
        private volatile KafkaConsumer<String, String> consumer;
        private Thread thread;

        Listener(String topic, Class<T> type, Consumer<KafkaRecord<T>> handler) {
            this.topic = topic;
            this.type = type;
            this.handler = handler;
        }

        void start() {
            active = true;
            thread = Thread.ofVirtual().name("ligero-kafka-" + topic).start(this::loop);
        }

        void stop() {
            active = false;
            KafkaConsumer<String, String> current = consumer;
            if (current != null) {
                current.wakeup();
            }
            if (thread != null) {
                try {
                    thread.join(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void loop() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers);
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId);
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.offsetReset);
            properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.maxPollRecords);
            properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
            properties.putAll(config.properties);
            try (KafkaConsumer<String, String> created =
                     new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
                consumer = created;
                created.subscribe(List.of(topic));
                while (active) {
                    ConsumerRecords<String, String> records = created.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> record : records) {
                        process(record);
                    }
                    if (!records.isEmpty()) {
                        created.commitSync();
                    }
                }
            } catch (WakeupException stopping) {
                // close() asked us to stop
            } catch (RuntimeException e) {
                if (active) {
                    log.error("Kafka consumer of {} stopped", topic, e);
                }
            } finally {
                consumer = null;
            }
        }

        private void process(ConsumerRecord<String, String> record) {
            T value;
            try {
                value = decode(record.value());
            } catch (RuntimeException unreadable) {
                deadLetter(record, unreadable);
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (Header header : record.headers()) {
                headers.put(header.key(), header.value() == null ? null
                    : new String(header.value(), StandardCharsets.UTF_8));
            }
            KafkaRecord<T> consumed = new KafkaRecord<>(record.topic(), record.partition(), record.offset(),
                record.key(), value, headers);
            long pause = config.retryBackoff.toMillis();
            for (int attempt = 1; ; attempt++) {
                try {
                    handler.accept(consumed);
                    return;
                } catch (RuntimeException failure) {
                    if (attempt > config.retries || !active) {
                        log.warn("Handler for {} failed on {}-{}@{} after {} attempt(s); dead-lettering it",
                            topic, record.topic(), record.partition(), record.offset(), attempt, failure);
                        deadLetter(record, failure);
                        return;
                    }
                    sleep(pause);
                    pause = Math.min(pause * 2, 30_000);
                }
            }
        }

        private T decode(String raw) {
            if (raw == null) {
                throw new MessagingException("Record without a value");
            }
            if (type == String.class) {
                return type.cast(raw);
            }
            T value = mapper.readJson(raw, type);
            if (value == null) {
                throw new MessagingException("Record value is JSON null");
            }
            return value;
        }

        private void deadLetter(ConsumerRecord<String, String> record, Exception cause) {
            ProducerRecord<String, String> dead = new ProducerRecord<>(record.topic() + ".DLT", record.key(), record.value());
            dead.headers().add(ORIGIN_HEADER, (record.topic() + "-" + record.partition() + "@" + record.offset())
                .getBytes(StandardCharsets.UTF_8));
            dead.headers().add(ERROR_HEADER, cause.getClass().getName().getBytes(StandardCharsets.UTF_8));
            try {
                producer().send(dead).get();
            } catch (Exception e) {
                // without a dead-letter copy, committing would lose the record: stop and let it be re-read
                throw new MessagingException("Could not dead-letter a record of " + record.topic(), e);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Settings; {@code bootstrapServers} is required, and {@code groupId} for consumers. */
    public static final class Builder {
        private String bootstrapServers;
        private String groupId;
        private BodyMapper mapper;
        private String offsetReset = "earliest";
        private int maxPollRecords = 100;
        private int retries = 2;
        private Duration retryBackoff = Duration.ofMillis(200);
        private final Map<String, Object> properties = new LinkedHashMap<>();

        /** {@code host:port[,host:port...]} of some brokers. */
        public Builder bootstrapServers(String bootstrapServers) {
            this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
            return this;
        }

        /** Consumer group: instances with the same group share the partitions. */
        public Builder groupId(String groupId) {
            this.groupId = Objects.requireNonNull(groupId, "groupId");
            return this;
        }

        public Builder bodyMapper(BodyMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            return this;
        }

        /** Where a new group starts: {@code earliest} (default) or {@code latest}. */
        public Builder offsetReset(String offsetReset) {
            if (!offsetReset.equals("earliest") && !offsetReset.equals("latest")) {
                throw new IllegalArgumentException("offsetReset must be earliest or latest");
            }
            this.offsetReset = offsetReset;
            return this;
        }

        /** Records handled per poll, and per commit (default 100). */
        public Builder maxPollRecords(int maxPollRecords) {
            if (maxPollRecords < 1) {
                throw new IllegalArgumentException("maxPollRecords must be >= 1");
            }
            this.maxPollRecords = maxPollRecords;
            return this;
        }

        /** Extra attempts for a failing handler before dead-lettering (default 2), and the first pause. */
        public Builder retries(int retries, Duration backoff) {
            if (retries < 0) {
                throw new IllegalArgumentException("retries must be >= 0");
            }
            this.retries = retries;
            this.retryBackoff = Objects.requireNonNull(backoff, "backoff");
            return this;
        }

        /** Any Kafka client setting, e.g. {@code security.protocol} = {@code SASL_SSL}. */
        public Builder property(String name, String value) {
            properties.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Kafka build() {
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                throw new IllegalStateException("Set bootstrapServers");
            }
            return new Kafka(this);
        }
    }
}
