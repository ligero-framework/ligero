package com.ligero.devtools;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bounded in-memory history of completed {@link RequestTrace}s plus a
 * subscriber list for the dashboard's live SSE stream.
 */
final class TraceStore {

    private final int capacity;
    private final Deque<RequestTrace> traces = new ArrayDeque<>();
    private final List<Consumer<RequestTrace>> subscribers = new CopyOnWriteArrayList<>();

    TraceStore(int capacity) {
        this.capacity = capacity;
    }

    void add(RequestTrace trace) {
        synchronized (traces) {
            if (traces.size() == capacity) {
                traces.removeLast();
            }
            traces.addFirst(trace);
        }
        for (Consumer<RequestTrace> subscriber : subscribers) {
            try {
                subscriber.accept(trace);
            } catch (RuntimeException ignored) {
                // a broken subscriber must not affect request handling
            }
        }
    }

    /** Most recent first. */
    List<RequestTrace> recent() {
        synchronized (traces) {
            return List.copyOf(traces);
        }
    }

    void subscribe(Consumer<RequestTrace> subscriber) {
        subscribers.add(subscriber);
    }

    void unsubscribe(Consumer<RequestTrace> subscriber) {
        subscribers.remove(subscriber);
    }
}
