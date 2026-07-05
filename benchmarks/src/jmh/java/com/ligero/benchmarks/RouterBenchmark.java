package com.ligero.benchmarks;

import com.ligero.http.Handler;
import com.ligero.router.Router;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Baseline for route-matching performance (roadmap fase 1.5): guards the
 * trie implementation against regressions. Run with
 * {@code ./gradlew :benchmarks:jmh}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RouterBenchmark {

    private static final Handler NOOP = ctx -> { };

    private Router router;

    @Setup
    public void setUp() {
        router = new Router();
        // 100 resources x 3 routes: static list, parametrized detail, nested
        for (int i = 0; i < 100; i++) {
            router.add("GET", "/api/v1/resource" + i, NOOP);
            router.add("GET", "/api/v1/resource" + i + "/{id}", NOOP);
            router.add("GET", "/api/v1/resource" + i + "/{id}/children/{childId}", NOOP);
        }
        router.add("GET", "/static/*path", NOOP);
    }

    @Benchmark
    public Object matchStatic() {
        return router.match("GET", "/api/v1/resource73");
    }

    @Benchmark
    public Object matchOneParam() {
        return router.match("GET", "/api/v1/resource73/12345");
    }

    @Benchmark
    public Object matchNestedParams() {
        return router.match("GET", "/api/v1/resource73/12345/children/678");
    }

    @Benchmark
    public Object matchWildcard() {
        return router.match("GET", "/static/css/site/main.css");
    }

    @Benchmark
    public Object matchMiss() {
        return router.match("GET", "/api/v9/unknown");
    }
}
