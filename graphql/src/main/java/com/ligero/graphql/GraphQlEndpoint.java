package com.ligero.graphql;

import com.ligero.http.Context;
import com.ligero.http.Handler;
import com.ligero.http.HttpException;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.introspection.Introspection;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Serves a GraphQL API (<a href="https://www.graphql-java.com">graphql-java</a>)
 * as a Ligero route, following the GraphQL-over-HTTP specification.
 *
 * <pre>{@code
 * GraphQlEndpoint graphql = GraphQlEndpoint.builder()
 *     .schemaResource("graphql/schema.graphqls")
 *     .wiring(wiring -> wiring
 *         .type("Query", type -> type
 *             .dataFetcher("book", env -> books.find(Long.parseLong(env.getArgument("id"))))
 *             .dataFetcher("books", env -> books.all()))
 *         .type("Mutation", type -> type
 *             .dataFetcher("addBook", env -> books.add(env.getArgument("title")))))
 *     .build();
 *
 * app.post("/graphql", graphql).get("/graphql", graphql);
 * }</pre>
 *
 * <p>Data fetchers reach the HTTP request (headers, the authenticated user,
 * services) with {@link #context(graphql.schema.DataFetchingEnvironment)}.</p>
 *
 * <p>Safe by default:</p>
 * <ul>
 *   <li>queries are bounded in size, depth (10) and complexity (1 000 fields),
 *       so one request cannot make the server do unbounded work;</li>
 *   <li>a {@code POST} must be {@code application/json} — an HTML form cannot
 *       send that cross-site — and a {@code GET} may only run queries, never
 *       mutations;</li>
 *   <li>an exception in a data fetcher is logged, and the client only sees
 *       "Internal error" — except for an {@link HttpException}, whose message
 *       is meant for the client;</li>
 *   <li>introspection can be turned off for public production APIs.</li>
 * </ul>
 */
public final class GraphQlEndpoint implements Handler {

    private static final Logger log = LoggerFactory.getLogger(GraphQlEndpoint.class);

    /** The GraphQL-over-HTTP media type; JSON is accepted too. */
    public static final String GRAPHQL_RESPONSE = "application/graphql-response+json";

    private static final String CONTEXT_KEY = Context.class.getName();

    private final GraphQL graphql;
    private final int maxQueryLength;
    private final int maxTokens;
    private final boolean introspection;
    private final com.ligero.spi.BodyMapper mapper;

    private GraphQlEndpoint(Builder builder) {
        GraphQLSchema schema = builder.schema != null ? builder.schema : buildSchema(builder);
        List<Instrumentation> limits = List.of(
            new MaxQueryDepthInstrumentation(builder.maxDepth),
            new MaxQueryComplexityInstrumentation(builder.maxComplexity));
        this.graphql = GraphQL.newGraphQL(schema)
            .instrumentation(new ChainedInstrumentation(limits))
            .defaultDataFetcherExceptionHandler(new SafeErrors())
            .build();
        this.maxQueryLength = builder.maxQueryLength;
        this.maxTokens = builder.maxTokens;
        this.introspection = builder.introspection;
        this.mapper = java.util.ServiceLoader.load(com.ligero.spi.BodyMapper.class).findFirst().orElse(null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The Ligero request a data fetcher is running for. */
    public static Context context(graphql.schema.DataFetchingEnvironment environment) {
        Context ctx = environment.getGraphQlContext().get(CONTEXT_KEY);
        if (ctx == null) {
            throw new IllegalStateException("Not running inside a GraphQlEndpoint request");
        }
        return ctx;
    }

    @Override
    public void handle(Context ctx) {
        Map<String, Object> request = readRequest(ctx);
        Object queryValue = request.get("query");
        if (!(queryValue instanceof String query) || query.isBlank()) {
            throw new HttpException(400, "The request needs a GraphQL \"query\"");
        }
        if (query.length() > maxQueryLength) {
            throw new HttpException(413, "The GraphQL query is longer than " + maxQueryLength + " characters");
        }
        Object operationName = request.get("operationName");
        if (operationName != null && !(operationName instanceof String)) {
            throw new HttpException(400, "\"operationName\" must be a string");
        }
        Object variables = request.get("variables");
        if (variables != null && !(variables instanceof Map)) {
            throw new HttpException(400, "\"variables\" must be an object");
        }
        ParserOptions parserOptions = ParserOptions.getDefaultOperationParserOptions().transform(
            options -> options.maxTokens(maxTokens));
        if ("GET".equals(ctx.method()) && isMutation(query, (String) operationName, parserOptions)) {
            ctx.header("Allow", "POST");
            throw new HttpException(405, "Mutations must be sent with POST");
        }
        @SuppressWarnings("unchecked")
        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query(query)
            .operationName((String) operationName)
            .variables(variables == null ? Map.of() : (Map<String, Object>) variables)
            .graphQLContext(context -> {
                context.put(CONTEXT_KEY, ctx);
                context.put(ParserOptions.class, parserOptions);
                if (!introspection) {
                    context.put(Introspection.INTROSPECTION_DISABLED, true);
                }
            })
            .locale(ctx.locale())
            .build();
        ExecutionResult result = graphql.execute(input);

        boolean graphqlResponse = acceptsGraphqlResponse(ctx);
        // per GraphQL over HTTP: with the new media type, a request that could
        // not be executed at all (parse/validation error: no "data") is a 400
        int status = graphqlResponse && !result.isDataPresent() ? 400 : 200;
        if (mapper == null) {
            throw new IllegalStateException("No BodyMapper found. Add ligero-json to the classpath.");
        }
        ctx.res().status(status)
            .contentType((graphqlResponse ? GRAPHQL_RESPONSE : "application/json") + "; charset=utf-8")
            .send(mapper.writeJson(result.toSpecification()));
    }

    private Map<String, Object> readRequest(Context ctx) {
        if ("GET".equals(ctx.method())) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("query", ctx.queryParam("query"));
            request.put("operationName", ctx.queryParam("operationName"));
            String variables = ctx.queryParam("variables");
            if (variables != null && !variables.isBlank()) {
                if (mapper == null) {
                    throw new HttpException(400, "Send variables with POST");
                }
                request.put("variables", mapper.readJson(variables, Map.class));
            }
            return request;
        }
        if (!"POST".equals(ctx.method())) {
            ctx.header("Allow", "GET, POST");
            throw new HttpException(405, "GraphQL takes GET or POST");
        }
        String contentType = ctx.header("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            // a cross-site HTML form cannot send application/json without a CORS preflight
            throw new HttpException(415, "GraphQL requests must be application/json");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.body(Map.class);
        return body;
    }

    private static boolean isMutation(String query, String operationName, ParserOptions options) {
        Document document;
        try {
            document = Parser.parse(new graphql.parser.ParserEnvironment.Builder()
                .document(query).parserOptions(options).build());
        } catch (InvalidSyntaxException e) {
            return false; // execution reports the syntax error
        }
        for (OperationDefinition operation : document.getDefinitionsOfType(OperationDefinition.class)) {
            boolean selected = operationName == null || operationName.equals(operation.getName());
            if (selected && operation.getOperation() != OperationDefinition.Operation.QUERY) {
                return true;
            }
        }
        return false;
    }

    private static boolean acceptsGraphqlResponse(Context ctx) {
        String accept = ctx.header("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(GRAPHQL_RESPONSE);
    }

    private static GraphQLSchema buildSchema(Builder builder) {
        if (builder.sdl.isEmpty()) {
            throw new IllegalStateException("Give the schema with schema(...), schemaResource(...) or a GraphQLSchema");
        }
        var registry = new SchemaParser().parse(String.join("\n", builder.sdl));
        RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
        builder.wiring.forEach(customizer -> customizer.accept(wiring));
        return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
    }

    /** Hides internal exception messages from clients; logs them instead. */
    private static final class SafeErrors implements DataFetcherExceptionHandler {
        @Override
        public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
                graphql.execution.DataFetcherExceptionHandlerParameters parameters) {
            Throwable exception = parameters.getException();
            if (exception instanceof java.util.concurrent.CompletionException && exception.getCause() != null) {
                exception = exception.getCause();
            }
            String message;
            Map<String, Object> extensions = new LinkedHashMap<>();
            if (exception instanceof HttpException http) {
                message = http.getMessage();
                extensions.put("status", http.getStatus());
            } else {
                log.error("GraphQL data fetcher failed at {}", parameters.getPath(), exception);
                message = "Internal error";
            }
            GraphQLError error = GraphqlErrorBuilder.newError()
                .message(message)
                .path(parameters.getPath())
                .location(parameters.getSourceLocation())
                .extensions(extensions.isEmpty() ? null : extensions)
                .build();
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult(error).build());
        }
    }

    /** Builder; a schema is required. */
    public static final class Builder {
        private final List<String> sdl = new ArrayList<>();
        private final List<Consumer<RuntimeWiring.Builder>> wiring = new ArrayList<>();
        private GraphQLSchema schema;
        private int maxDepth = 10;
        private int maxComplexity = 1_000;
        private int maxQueryLength = 20_000;
        private int maxTokens = 15_000;
        private boolean introspection = true;

        /** Schema in GraphQL SDL; may be called several times to combine files. */
        public Builder schema(String sdl) {
            this.sdl.add(Objects.requireNonNull(sdl, "sdl"));
            return this;
        }

        /** Schema SDL from a classpath resource, e.g. {@code graphql/schema.graphqls}. */
        public Builder schemaResource(String resource) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            try (InputStream in = (loader != null ? loader : GraphQlEndpoint.class.getClassLoader())
                    .getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalArgumentException("No classpath resource " + resource);
                }
                return schema(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** A schema built in code; replaces {@link #schema(String)} and {@link #wiring}. */
        public Builder schema(GraphQLSchema schema) {
            this.schema = Objects.requireNonNull(schema, "schema");
            return this;
        }

        /** Connects the SDL to code: data fetchers, scalars, type resolvers. */
        public Builder wiring(Consumer<RuntimeWiring.Builder> wiring) {
            this.wiring.add(Objects.requireNonNull(wiring, "wiring"));
            return this;
        }

        /** Deepest selection nesting allowed (default 10). */
        public Builder maxDepth(int maxDepth) {
            this.maxDepth = positive(maxDepth, "maxDepth");
            return this;
        }

        /** Most fields a query may select, counting nested ones (default 1 000). */
        public Builder maxComplexity(int maxComplexity) {
            this.maxComplexity = positive(maxComplexity, "maxComplexity");
            return this;
        }

        /** Longest query text accepted, in characters (default 20 000). */
        public Builder maxQueryLength(int maxQueryLength) {
            this.maxQueryLength = positive(maxQueryLength, "maxQueryLength");
            return this;
        }

        /** Most parser tokens in a query (default 15 000). */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = positive(maxTokens, "maxTokens");
            return this;
        }

        /** Whether clients may query the schema itself ({@code __schema}); on by default. */
        public Builder introspection(boolean introspection) {
            this.introspection = introspection;
            return this;
        }

        public GraphQlEndpoint build() {
            return new GraphQlEndpoint(this);
        }

        private static int positive(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be >= 1");
            }
            return value;
        }
    }
}
