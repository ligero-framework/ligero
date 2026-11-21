package com.ligero.kafka;

import com.ligero.json.JacksonBodyMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real broker in Docker; skipped where Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class KafkaTest {

    public record OrderPlaced(long id, String customer) {
    }

    @Container
    static final KafkaContainer BROKER = new KafkaContainer("apache/kafka:4.1.0");

    private Kafka kafka;

    @AfterEach
    void close() {
        if (kafka != null) {
            kafka.close();
        }
    }

    private static Kafka.Builder client() {
        return Kafka.builder().bootstrapServers(BROKER.getBootstrapServers())
            .groupId("test-" + UUID.randomUUID());
    }

    private static String topic() {
        return "t-" + UUID.randomUUID();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("condition within 60 s").isTrue();
    }

    @Test
    void producesAndConsumesJsonWithKeys() throws Exception {
        String topic = topic();
        List<Kafka.KafkaRecord<OrderPlaced>> received = new CopyOnWriteArrayList<>();
        kafka = client().build().listenToRecords(topic, OrderPlaced.class, received::add);
        kafka.start();
        kafka.send(topic, "42", new OrderPlaced(42, "ana")).get();
        kafka.send(topic, new OrderPlaced(43, "bo")).get();
        await(() -> received.size() == 2);
        assertThat(received).extracting(Kafka.KafkaRecord::value)
            .containsExactlyInAnyOrder(new OrderPlaced(42, "ana"), new OrderPlaced(43, "bo"));
        assertThat(received).anyMatch(record -> "42".equals(record.key()) && record.topic().equals(topic));
    }

    @Test
    void retriesThenDeadLetters() throws Exception {
        String topic = topic();
        AtomicInteger attempts = new AtomicInteger();
        List<OrderPlaced> handled = new CopyOnWriteArrayList<>();
        List<Kafka.KafkaRecord<String>> dead = new CopyOnWriteArrayList<>();
        kafka = client().retries(2, Duration.ofMillis(10)).bodyMapper(new JacksonBodyMapper()).build()
            .listen(topic, OrderPlaced.class, order -> {
                if (order.id() == 1 && attempts.incrementAndGet() < 2) {
                    throw new IllegalStateException("flaky");
                }
                if (order.id() == 2) {
                    throw new IllegalStateException("poison");
                }
                handled.add(order);
            })
            .listenToRecords(topic + ".DLT", String.class, dead::add);
        kafka.start();
        kafka.send(topic, new OrderPlaced(1, "cy")).get();
        kafka.send(topic, new OrderPlaced(2, "dee")).get();
        kafka.send(topic, "{not json").get();
        kafka.send(topic, new OrderPlaced(3, "ed")).get();

        await(() -> handled.size() == 2 && dead.size() == 2);
        assertThat(handled).extracting(OrderPlaced::id).containsExactly(1L, 3L);
        assertThat(dead).anyMatch(record -> record.value().contains("\"id\":2")
            && record.headers().get(Kafka.ERROR_HEADER).equals(IllegalStateException.class.getName())
            && record.headers().get(Kafka.ORIGIN_HEADER).startsWith(topic + "-"));
        assertThat(dead).anyMatch(record -> record.value().equals("{not json"));
    }

    @Test
    void misuseFailsEarly() {
        assertThatThrownBy(() -> Kafka.builder().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Kafka.builder().offsetReset("middle")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Kafka.builder().maxPollRecords(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Kafka.builder().retries(-1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        Kafka noGroup = Kafka.builder().bootstrapServers("localhost:1").build();
        assertThatThrownBy(() -> noGroup.listen("t", String.class, s -> { })).isInstanceOf(IllegalStateException.class);
        noGroup.close();
    }
}
