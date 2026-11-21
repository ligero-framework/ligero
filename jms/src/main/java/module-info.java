/** Ligero JMS: JSON messages over any Jakarta Messaging broker. */
module com.ligero.jms {
    requires transitive com.ligero.core;
    requires transitive jakarta.messaging;
    requires org.slf4j;

    exports com.ligero.jms;

    uses com.ligero.spi.BodyMapper;
}
