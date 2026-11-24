/** Ligero AMQP: JSON messages over AMQP 1.0 (Qpid ProtonJ2). */
module com.ligero.amqp {
    requires transitive com.ligero.core;
    requires transitive org.apache.qpid.protonj2.client;
    requires org.slf4j;

    exports com.ligero.amqp;

    uses com.ligero.spi.BodyMapper;
}
