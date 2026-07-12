package com.ligero.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A tiny in-process event bus: publish an event object and every handler
 * subscribed to its type (or a supertype/interface it implements) is called,
 * synchronously, in subscription order. A handler that throws is logged and
 * does not stop the others.
 *
 * <pre>{@code
 * Events events = new Events();
 * events.subscribe(OrderPlaced.class, e -> mailer.confirm(e.orderId()));
 * events.publish(new OrderPlaced(42));
 * }</pre>
 *
 * <p>Decouples producers from consumers without a message broker. Register it
 * as a bean and inject it where you publish or subscribe. Thread-safe.</p>
 */
public final class Events {

    private static final Logger log = LoggerFactory.getLogger(Events.class);

    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

    /** Subscribes {@code handler} to events assignable to {@code type}. */
    @SuppressWarnings("unchecked")
    public <E> void subscribe(Class<E> type, Consumer<? super E> handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
            .add((Consumer<Object>) handler);
    }

    /** Delivers {@code event} to every handler registered for a matching type. */
    public void publish(Object event) {
        Class<?> eventType = event.getClass();
        int delivered = 0;
        for (Map.Entry<Class<?>, List<Consumer<Object>>> entry : handlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventType)) {
                for (Consumer<Object> handler : entry.getValue()) {
                    try {
                        handler.accept(event);
                        delivered++;
                    } catch (RuntimeException e) {
                        log.error("Event handler for {} failed", eventType.getSimpleName(), e);
                    }
                }
            }
        }
        if (delivered == 0) {
            log.debug("No subscribers for event {}", eventType.getSimpleName());
        }
    }
}
