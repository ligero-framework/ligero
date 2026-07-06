/** Ligero MetricsCollector adapter for Micrometer registries. */
module com.ligero.metrics.micrometer {
    requires transitive com.ligero.core;
    requires transitive micrometer.core;

    exports com.ligero.metrics.micrometer;
}
