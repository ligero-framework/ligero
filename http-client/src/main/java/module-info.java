/**
 * Ligero HTTP client: call other services with a fluent {@code RestClient}
 * or with interfaces whose implementation {@code ligero-processor} generates
 * at compile time.
 */
module com.ligero.client {
    requires transitive com.ligero.core;
    requires transitive java.net.http;
    requires org.slf4j;

    exports com.ligero.client;

    uses com.ligero.spi.BodyMapper;
}
