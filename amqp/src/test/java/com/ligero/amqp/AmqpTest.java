package com.ligero.amqp;

import com.ligero.json.JacksonBodyMapper;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An embedded ActiveMQ Artemis broker speaking AMQP 1.0 on a local port. */
class AmqpTest {

    public record OrderPlaced(long id, String customer) {
    }

    private static EmbeddedActiveMQ broker;
    private static int port;
    private Amqp amqp;

    @BeforeAll
    static void startBroker() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        ConfigurationImpl config = new ConfigurationImpl();
        config.setPersistenceEnabled(false);
        config.setSecurityEnabled(false);
        config.addAcceptorConfiguration("amqp", "tcp://127.0.0.1:" + port + "?protocols=AMQP");
        config.addAddressSetting("#", new AddressSettings()
            // queue semantics for addresses created on first use (Artemis defaults to multicast)
            .setDefaultAddressRoutingType(org.apache.activemq.artemis.api.core.RoutingType.ANYCAST)
            .setDefaultQueueRoutingType(org.apache.activemq.artemis.api.core.RoutingType.ANYCAST)
            .setMaxDeliveryAttempts(3)
            .setDeadLetterAddress(SimpleString.of("DLQ"))
            .setAutoCreateDeadLetterResources(true));
        broker = new EmbeddedActiveMQ().setConfiguration(config);
        broker.start();
    }

    @AfterAll
    static void stopBroker() throws Exception {
        broker.stop();
    }

    @AfterEach
    void close() {
        if (amqp != null) {
            amqp.close();
        }
    }

    private static Amqp.Builder client() {
        return Amqp.builder().host("127.0.0.1").port(port);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("condition within 15 s").isTrue();
    }

    @Test
    void sendsAndReceivesJson() throws Exception {
        List<OrderPlaced> received = new CopyOnWriteArrayList<>();
        amqp = client().build().listen("orders", OrderPlaced.class, received::add);
        amqp.start();
        amqp.send("orders", new OrderPlaced(1, "ana"));
        amqp.send("orders", new OrderPlaced(2, "bo"), Map.of("priority", 5));
        await(() -> received.size() == 2);
        assertThat(received).containsExactly(new OrderPlaced(1, "ana"), new OrderPlaced(2, "bo"));
    }

    @Test
    void textAndLateListeners() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        amqp = client().bodyMapper(new JacksonBodyMapper()).prefetch(5).sendTimeout(Duration.ofSeconds(5)).build();
        amqp.start();
        amqp.listen("texts", String.class, received::add);
        amqp.send("texts", "plain words");
        await(() -> received.contains("plain words"));
    }

    @Test
    void failuresAreRedeliveredThenDeadLettered() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<OrderPlaced> handled = new CopyOnWriteArrayList<>();
        List<String> dead = new CopyOnWriteArrayList<>();
        amqp = client().build()
            .listen("flaky", OrderPlaced.class, order -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new IllegalStateException("downstream is down");
                }
                handled.add(order);
            })
            .listen("poison", OrderPlaced.class, order -> {
                throw new IllegalStateException("always");
            })
            .listen("DLQ", String.class, dead::add);
        amqp.start();
        amqp.send("flaky", new OrderPlaced(7, "cy"));
        await(() -> handled.size() == 1);
        assertThat(attempts).hasValue(2);

        amqp.send("poison", new OrderPlaced(8, "dee"));
        await(() -> dead.stream().anyMatch(body -> body.contains("\"id\":8")));
    }

    @Test
    void unreadableMessagesAreRejectedNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<OrderPlaced> received = new CopyOnWriteArrayList<>();
        amqp = client().build().listen("strict", OrderPlaced.class, order -> {
            calls.incrementAndGet();
            received.add(order);
        });
        amqp.start();
        amqp.send("strict", "{not json");
        amqp.send("strict", new OrderPlaced(9, "ed"));
        await(() -> received.size() == 1);
        Thread.sleep(200);
        assertThat(calls).hasValue(1);
    }

    @Test
    void misuseAndUnreachableBrokers() {
        assertThatThrownBy(() -> Amqp.builder().port(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Amqp.builder().prefetch(0)).isInstanceOf(IllegalArgumentException.class);
        Amqp nowhere = Amqp.builder().host("127.0.0.1").port(1).user("u").password("p").tls(false)
            .sendTimeout(Duration.ofSeconds(2)).build();
        assertThatThrownBy(() -> nowhere.send("q", "x")).isInstanceOf(MessagingException.class);
        nowhere.close();
    }
}
