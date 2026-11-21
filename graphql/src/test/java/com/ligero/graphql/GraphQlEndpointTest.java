package com.ligero.graphql;

import com.ligero.http.HttpException;
import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphQlEndpointTest {

    private static final String SCHEMA = """
        type Query {
          book(id: ID!): Book
          books: [Book!]!
          whoami: String
          broken: String
          forbidden: String
        }
        type Mutation { addBook(title: String!): Book! }
        type Book { id: ID! title: String! related: [Book!]! }
        """;

    private static final List<Map<String, Object>> BOOKS = new CopyOnWriteArrayList<>(List.of(
        Map.of("id", "1", "title", "Dune"), Map.of("id", "2", "title", "Solaris")));

    private static LigeroTest server;
    private static LigeroTest closed;

    private static GraphQlEndpoint.Builder endpoint() {
        return GraphQlEndpoint.builder()
            .schema(SCHEMA)
            .wiring(wiring -> wiring
                .type("Query", type -> type
                    .dataFetcher("book", env -> BOOKS.stream()
                        .filter(book -> book.get("id").equals(env.getArgument("id"))).findFirst().orElse(null))
                    .dataFetcher("books", env -> BOOKS)
                    .dataFetcher("whoami", env -> GraphQlEndpoint.context(env).header("X-User"))
                    .dataFetcher("broken", env -> {
                        throw new IllegalStateException("db password is hunter2");
                    })
                    .dataFetcher("forbidden", env -> {
                        throw new HttpException(403, "Not your shelf");
                    }))
                .type("Book", type -> type.dataFetcher("related", env -> BOOKS))
                .type("Mutation", type -> type.dataFetcher("addBook", env -> {
                    Map<String, Object> book = Map.of("id", String.valueOf(BOOKS.size() + 1),
                        "title", env.getArgument("title"));
                    BOOKS.add(book);
                    return book;
                })));
    }

    @BeforeAll
    static void start() {
        GraphQlEndpoint graphql = endpoint().maxDepth(4).maxComplexity(50).maxQueryLength(2_000).build();
        server = LigeroTest.create(app -> app.post("/graphql", graphql).get("/graphql", graphql)
            .put("/graphql", graphql));
        GraphQlEndpoint noIntrospection = endpoint().introspection(false).build();
        closed = LigeroTest.create(app -> app.post("/graphql", noIntrospection));
    }

    @AfterAll
    static void stop() {
        server.close();
        closed.close();
    }

    private static LigeroTest.TestResponse post(LigeroTest target, String json) {
        return target.post("/graphql").json(json).execute();
    }

    @Test
    void runsQueriesWithVariablesAndMutations() {
        LigeroTest.TestResponse response = post(server,
            "{\"query\":\"query One($id: ID!) { book(id: $id) { title } }\",\"variables\":{\"id\":\"2\"}}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"data\":{\"book\":{\"title\":\"Solaris\"}}}");

        LigeroTest.TestResponse added = post(server, "{\"query\":\"mutation { addBook(title: \\\"Ubik\\\") { title } }\"}");
        assertThat(added.body()).contains("\"addBook\":{\"title\":\"Ubik\"}");
    }

    @Test
    void dataFetchersSeeTheHttpRequest() {
        LigeroTest.TestResponse response = server.post("/graphql").header("X-User", "ana")
            .json("{\"query\":\"{ whoami }\"}").execute();
        assertThat(response.body()).contains("\"whoami\":\"ana\"");
    }

    @Test
    void internalErrorsAreHiddenButHttpExceptionsAreShown() {
        String broken = post(server, "{\"query\":\"{ broken }\"}").body();
        assertThat(broken).contains("Internal error").doesNotContain("hunter2");
        String forbidden = post(server, "{\"query\":\"{ forbidden }\"}").body();
        assertThat(forbidden).contains("Not your shelf").contains("\"status\":403");
    }

    @Test
    void limitsDepthComplexityAndSize() {
        String deep = post(server, "{\"query\":\"{ books { related { related { related { related { title } } } } } }\"}").body();
        assertThat(deep).contains("maximum query depth exceeded");
        StringBuilder aliases = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            aliases.append("b").append(i).append(": books { title id } ");
        }
        String wide = post(server, "{\"query\":\"{ " + aliases + "}\"}").body();
        assertThat(wide).contains("maximum query complexity exceeded");
        assertThat(post(server, "{\"query\":\"{ " + "books { title } ".repeat(200) + "}\"}").status()).isEqualTo(413);
    }

    @Test
    void getRunsQueriesButNeverMutations() {
        String query = URLEncoder.encode("{ book(id: \"1\") { title } }", StandardCharsets.UTF_8);
        assertThat(server.get("/graphql?query=" + query).execute().body()).contains("Dune");
        String withVariables = "/graphql?query=" + URLEncoder.encode("query($id: ID!) { book(id: $id) { title } }",
            StandardCharsets.UTF_8) + "&variables=" + URLEncoder.encode("{\"id\":\"2\"}", StandardCharsets.UTF_8);
        assertThat(server.get(withVariables).execute().body()).contains("Solaris");
        String mutation = URLEncoder.encode("mutation { addBook(title: \"x\") { id } }", StandardCharsets.UTF_8);
        LigeroTest.TestResponse refused = server.get("/graphql?query=" + mutation).execute();
        assertThat(refused.status()).isEqualTo(405);
        assertThat(refused.header("Allow")).isEqualTo("POST");
    }

    @Test
    void onlyJsonPostsAreAccepted() {
        assertThat(server.post("/graphql").header("Content-Type", "application/x-www-form-urlencoded")
            .body("query={books{title}}").execute().status()).isEqualTo(415);
        assertThat(server.put("/graphql").json("{}").execute().status()).isEqualTo(405);
        assertThat(post(server, "{}").status()).isEqualTo(400);
        assertThat(post(server, "{\"query\":\"{ books { title } }\",\"variables\":[1]}").status()).isEqualTo(400);
        assertThat(post(server, "{\"query\":\"{ books { title } }\",\"operationName\":5}").status()).isEqualTo(400);
    }

    @Test
    void theGraphqlResponseMediaTypeUses400ForRequestErrors() {
        LigeroTest.TestResponse invalid = server.post("/graphql").header("Accept", GraphQlEndpoint.GRAPHQL_RESPONSE)
            .json("{\"query\":\"{ nope }\"}").execute();
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.header("Content-Type")).startsWith(GraphQlEndpoint.GRAPHQL_RESPONSE);
        assertThat(post(server, "{\"query\":\"{ nope }\"}").status()).isEqualTo(200);
        assertThat(post(server, "{\"query\":\"{ books { \"}").body()).contains("errors");
    }

    @Test
    void introspectionCanBeTurnedOff() {
        String query = "{\"query\":\"{ __schema { types { name } } }\"}";
        assertThat(post(server, query).body()).contains("\"Book\"");
        assertThat(post(closed, query).body()).contains("errors").doesNotContain("\"Book\"");
    }

    @Test
    void misconfigurationFailsEarly() {
        assertThatThrownBy(() -> GraphQlEndpoint.builder().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GraphQlEndpoint.builder().maxDepth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GraphQlEndpoint.builder().schemaResource("missing.graphqls"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(GraphQlEndpoint.builder().schemaResource("library.graphqls")
            .wiring(w -> w.type("Query", t -> t.dataFetcher("hello", env -> "hi"))).maxTokens(100).build()).isNotNull();
    }
}
