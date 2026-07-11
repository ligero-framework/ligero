package com.ligero.devtools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One request as seen by devtools: method, path, status, elapsed time and
 * the ordered list of spied bean calls made while handling it.
 *
 * <p>A request is handled by a single (virtual) thread, so {@link Call}s are
 * appended without contention; volatile fields make the completed trace safe
 * to read from the dashboard's SSE thread.</p>
 */
public final class RequestTrace {

    /** One spied call: {@code bean.method(args) -> result} with depth for the tree view. */
    public static final class Call {
        private final int order;
        private final int depth;
        private final String bean;       // implementation class (simple name)
        private final String declaredBy; // interface the bean was bound as
        private final String stereotype;
        private final String method;
        private final String args;
        private volatile String result;
        private volatile String error;
        private volatile long durationUs;

        Call(int order, int depth, String bean, String declaredBy, String stereotype,
             String method, String args) {
            this.order = order;
            this.depth = depth;
            this.bean = bean;
            this.declaredBy = declaredBy;
            this.stereotype = stereotype;
            this.method = method;
            this.args = args;
        }

        void complete(String result, String error, long durationUs) {
            this.result = result;
            this.error = error;
            this.durationUs = durationUs;
        }

        public int order() { return order; }
        public int depth() { return depth; }
        public String bean() { return bean; }
        public String declaredBy() { return declaredBy; }
        public String stereotype() { return stereotype; }
        public String method() { return method; }
        public String args() { return args; }
        public String result() { return result; }
        public String error() { return error; }
        public long durationUs() { return durationUs; }
    }

    private final String id;
    private final String method;
    private final String path;
    private final long startedAtMs;
    private final long startNanos;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile int status;
    private volatile long durationUs;
    private volatile String route;         // matched route pattern, e.g. /users/:id
    private volatile String requestJson;   // path/query/body inputs, as JSON
    private volatile String responseJson;  // response body handed to ctx.json(...), as JSON
    private int depth;
    private int nextOrder;

    RequestTrace(String id, String method, String path) {
        this.id = id;
        this.method = method;
        this.path = path;
        this.startedAtMs = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
    }

    Call enter(String bean, String declaredBy, String stereotype, String method, String args) {
        Call call = new Call(nextOrder++, depth++, bean, declaredBy, stereotype, method, args);
        calls.add(call);
        return call;
    }

    void exit() {
        depth--;
    }

    void finish(int status) {
        this.status = status;
        this.durationUs = (System.nanoTime() - startNanos) / 1_000;
    }

    /** Records the matched route pattern and the request inputs (as JSON). */
    void describe(String route, String requestJson) {
        this.route = route;
        this.requestJson = requestJson;
    }

    /** Records the response body handed to {@code ctx.json(...)} (as JSON). */
    void respondedWith(String responseJson) {
        this.responseJson = responseJson;
    }

    public String id() { return id; }
    public String method() { return method; }
    public String path() { return path; }
    public long startedAtMs() { return startedAtMs; }
    public int status() { return status; }
    public long durationUs() { return durationUs; }
    public String route() { return route; }
    public String requestJson() { return requestJson; }
    public String responseJson() { return responseJson; }

    /** Snapshot of the recorded calls, in entry order. */
    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }
}
