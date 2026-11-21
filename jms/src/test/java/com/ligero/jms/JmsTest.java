package com.ligero.jms;

import com.ligero.json.JacksonBodyMapper;

import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmsTest {

    public record OrderPlaced(long id, String customer) {
    }

    private static EmbeddedActiveMQ broker;
    private static ActiveMQConnectionFactory factory;
    private Jms jms;

    @BeforeAll
    static void startBroker() throws Exception {
        ConfigurationImpl config = new ConfigurationImpl();
        config.setPersistenceEnabled(false);
        config.setSecurityEnabled(false);
        config.addAcceptorConfiguration(new TransportConfiguration(InVMAcceptorFactory.class.getName()));
        config.addAddressSetting("#", new AddressSettings()
            .setMaxDeliveryAttempts(3)
            .setRedeliveryDelay(0)
            .setDeadLetterAddress(org.apache.activemq.artemis.api.core.SimpleString.of("DLQ"))
            .setAutoCreateDeadLetterResources(true));
        broker = new EmbeddedActiveMQ().setConfiguration(config);
        broker.start();
        factory = new ActiveMQConnectionFactory("vm://0");
    }

    @AfterAll
    static void stopBroker() throws Exception {
        factory.close();
        broker.stop();
    }

    @AfterEach
    void close() {
        if (jms != null) {
            jms.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("condition within 10 s").isTrue();
    }

    @Test
    void sendsAndReceivesJson() throws Exception {
        List<OrderPlaced> received = new CopyOnWriteArrayList<>();
        jms = Jms.of(factory).listen("orders", OrderPlaced.class, 2, received::add);
        jms.start();
        jms.send("orders", new OrderPlaced(1, "ana"));
        jms.send("orders", new OrderPlaced(2, "bo"), Map.of("priority", 5, "region", "eu"));
        await(() -> received.size() == 2);
        assertThat(received).containsExactlyInAnyOrder(new OrderPlaced(1, "ana"), new OrderPlaced(2, "bo"));
    }

    @Test
    void stringsTravelAsIsAndLateListenersStart() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        jms = Jms.of(factory, new JacksonBodyMapper());
        jms.start();
        jms.listen("texts", String.class, received::add);
        jms.send("texts", "plain words");
        await(() -> received.contains("plain words"));
    }

    @Test
    void aFailingHandlerGetsTheMessageAgainUntilTheBrokerGivesUp() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<OrderPlaced> handled = new CopyOnWriteArrayList<>();
        jms = Jms.of(factory).listen("flaky", OrderPlaced.class, order -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("downstream is down");
            }
            handled.add(order);
        });
        jms.start();
        jms.send("flaky", new OrderPlaced(7, "cy"));
        await(() -> handled.size() == 1);
        assertThat(attempts).hasValue(2);

        List<String> dead = new CopyOnWriteArrayList<>();
        jms.listen("DLQ", String.class, dead::add);
        jms.listen("poison", OrderPlaced.class, order -> {
            throw new IllegalStateException("always");
        });
        jms.send("poison", new OrderPlaced(8, "dee"));
        await(() -> dead.size() == 1);
        assertThat(dead.get(0)).contains("\"id\":8");
    }

    @Test
    void serializedJavaObjectsAreNeverRead() throws Exception {
        List<OrderPlaced> received = new CopyOnWriteArrayList<>();
        jms = Jms.of(factory).listen("mixed", OrderPlaced.class, received::add);
        jms.start();
        try (Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer producer = session.createProducer(session.createQueue("mixed"))) {
            producer.send(session.createObjectMessage(new java.util.ArrayList<>(List.of("gadget"))));
            producer.send(session.createTextMessage("{not json"));
        }
        jms.send("mixed", new OrderPlaced(9, "ed"));
        await(() -> received.size() == 1);
        assertThat(received).containsExactly(new OrderPlaced(9, "ed"));
    }

    @Test
    void misuseFailsClearly() {
        jms = Jms.of(factory);
        assertThatThrownBy(() -> jms.listen("q", String.class, 0, s -> { }))
            .isInstanceOf(IllegalArgumentException.class);
        Jms closedBroker = Jms.of(new ActiveMQConnectionFactory("vm://99"));
        assertThatThrownBy(() -> closedBroker.send("q", "x")).isInstanceOf(MessagingException.class);
        closedBroker.close();
    }
}
