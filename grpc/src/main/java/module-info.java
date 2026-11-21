/** Ligero gRPC: gRPC services next to the HTTP server, on grpc-java and virtual threads. */
module com.ligero.grpc {
    requires transitive com.ligero.core;
    requires transitive io.grpc;
    requires io.grpc.services;
    requires io.grpc.netty.shaded;
    requires org.slf4j;

    exports com.ligero.grpc;
}
