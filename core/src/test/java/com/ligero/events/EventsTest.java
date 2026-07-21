package com.ligero.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventsTest {

    interface DomainEvent {
    }

    record OrderPlaced(int id) implements DomainEvent {
    }

    @Test
    void deliversToExactTypeAndSupertypeSubscribers() {
        Events events = new Events();
        List<String> log = new ArrayList<>();

        events.subscribe(OrderPlaced.class, e -> log.add("exact:" + e.id()));
        events.subscribe(DomainEvent.class, e -> log.add("iface"));

        events.publish(new OrderPlaced(7));

        assertThat(log).containsExactlyInAnyOrder("exact:7", "iface");
    }

    @Test
    void aThrowingHandlerDoesNotStopTheOthers() {
        Events events = new Events();
        List<String> log = new ArrayList<>();
        events.subscribe(OrderPlaced.class, e -> {
            throw new RuntimeException("boom");
        });
        events.subscribe(OrderPlaced.class, e -> log.add("second"));

        events.publish(new OrderPlaced(1));

        assertThat(log).containsExactly("second");
    }

    @Test
    void publishingWithNoSubscribersIsANoOp() {
        Events events = new Events();
        events.publish(new OrderPlaced(1)); // must not throw
    }
}
