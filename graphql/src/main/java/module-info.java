/** Ligero GraphQL: a GraphQL API served as a Ligero route, on graphql-java. */
module com.ligero.graphql {
    requires transitive com.ligero.core;
    requires transitive com.graphqljava;
    requires org.slf4j;

    exports com.ligero.graphql;
}
