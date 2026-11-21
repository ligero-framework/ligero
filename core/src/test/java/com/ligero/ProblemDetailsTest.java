package com.ligero;

import com.ligero.config.LigeroConfig;
import com.ligero.http.BadRequestException;
import com.ligero.http.NotFoundException;
import com.ligero.http.ProblemDetail;
import com.ligero.http.ProblemException;
import com.ligero.validation.ValidationException;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemDetailsTest {

    private final LigeroPipelineTest.FakeEngine engine = new LigeroPipelineTest.FakeEngine();

    private void start(Consumer<Ligero> setup) throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.engine(engine);
        setup.accept(app);
        app.start();
    }

    private FakeResponse exchange(String method, String uri) throws Exception {
        FakeResponse response = new FakeResponse();
        engine.rootHandler.handle(FakeRequest.of(method, uri), response);
        return response;
    }

    @Test
    void rendersTheStandardMembersAndExtensions() {
        ProblemDetail problem = ProblemDetail.of(403)
            .type("https://example.com/probs/out-of-credit")
            .title("You do not have enough credit")
            .detail("Your balance is 30, but that costs 50")
            .instance("/account/12345/msgs/abc")
            .with("balance", 30)
            .with("accounts", List.of("/account/12345", "/account/67890"))
            .with("limits", Map.of("daily", 1.5))
            .with("note", null);

        assertThat(problem.toJson()).isEqualTo("{\"type\":\"https://example.com/probs/out-of-credit\","
            + "\"title\":\"You do not have enough credit\",\"status\":403,"
            + "\"detail\":\"Your balance is 30, but that costs 50\",\"instance\":\"/account/12345/msgs/abc\","
            + "\"balance\":30,\"accounts\":[\"/account/12345\",\"/account/67890\"],"
            + "\"limits\":{\"daily\":1.5},\"note\":null}");
    }

    @Test
    void defaultsToAboutBlankAndTheReasonPhrase() {
        assertThat(ProblemDetail.of(404).toJson())
            .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
        assertThat(ProblemDetail.reasonPhrase(422)).isEqualTo("Unprocessable Content");
        assertThat(ProblemDetail.reasonPhrase(599)).isEqualTo("Error");
    }

    @Test
    void escapesStringsSoTheyCannotBreakOutOfJsonOrHtml() {
        String json = ProblemDetail.of(400, "a\"b\\c\n</script>\u0001").toJson();
        assertThat(json).contains("\"detail\":\"a\\\"b\\\\c\\n\\u003c/script>\\u0001\"");
    }

    @Test
    void rejectsStandardMemberNamesAsExtensionsAndBadStatuses() {
        assertThatThrownBy(() -> ProblemDetail.of(400).with("status", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemDetail.of(42)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteNumbersAndUnknownObjectsStayValidJson() {
        String json = ProblemDetail.of(400).with("nan", Double.NaN).with("when", new StringBuilder("now"))
            .with("array", new Object[] {1, true}).toJson();
        assertThat(json).contains("\"nan\":null", "\"when\":\"now\"", "\"array\":[1,true]");
    }

    @Test
    void frameworkErrorsKeepTheirClassicBodyByDefault() throws Exception {
        start(app -> app.get("/x", ctx -> {
            throw new NotFoundException("No such thing");
        }));
        FakeResponse response = exchange("GET", "/x");
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.contentTypeValue()).isEqualTo("application/json");
        assertThat(response.body()).isEqualTo("{\"status\":404,\"error\":\"No such thing\"}");
    }

    @Test
    void problemDetailsModeRendersEveryErrorAsAProblem() throws Exception {
        start(app -> app.problemDetails(true)
            .get("/missing", ctx -> {
                throw new NotFoundException("No such user");
            })
            .post("/users", ctx -> {
                throw new ValidationException(List.of("name: must not be blank", "age: must be positive"));
            })
            .get("/boom", ctx -> {
                throw new IllegalStateException("secret internals");
            }));

        FakeResponse missing = exchange("GET", "/missing");
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(missing.contentTypeValue()).isEqualTo("application/problem+json");
        assertThat(missing.body()).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
            + "\"detail\":\"No such user\",\"instance\":\"/missing\"}");

        FakeResponse invalid = exchange("POST", "/users");
        assertThat(invalid.getStatus()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"detail\":\"Validation failed\"",
            "\"errors\":[\"name: must not be blank\",\"age: must be positive\"]");

        FakeResponse boom = exchange("GET", "/boom");
        assertThat(boom.getStatus()).isEqualTo(500);
        assertThat(boom.body()).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Internal Server Error\","
            + "\"status\":500,\"instance\":\"/boom\"}");

        FakeResponse unrouted = exchange("DELETE", "/missing");
        assertThat(unrouted.getStatus()).isEqualTo(405);
        assertThat(unrouted.headerValue("Allow")).isEqualTo("GET");
        assertThat(unrouted.contentTypeValue()).isEqualTo("application/problem+json");
    }

    @Test
    void aProblemExceptionIsAlwaysAProblem() throws Exception {
        start(app -> app.get("/orders/{id}", ctx -> {
            throw new ProblemException(ProblemDetail.of(409)
                .type("https://example.com/probs/out-of-stock")
                .title("Out of stock")
                .detail("Item " + ctx.pathParam("id") + " is no longer available")
                .with("item", ctx.pathParamAsLong("id")));
        }));
        FakeResponse response = exchange("GET", "/orders/42");
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.contentTypeValue()).isEqualTo("application/problem+json");
        assertThat(response.body()).isEqualTo("{\"type\":\"https://example.com/probs/out-of-stock\","
            + "\"title\":\"Out of stock\",\"status\":409,\"detail\":\"Item 42 is no longer available\","
            + "\"instance\":\"/orders/42\",\"item\":42}");
    }

    @Test
    void exceptionHandlersCanAnswerWithAProblem() throws Exception {
        start(app -> app
            .exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.problem(ProblemDetail.of(422, e.getMessage()).instance("/custom")))
            .get("/x", ctx -> {
                throw new IllegalArgumentException("bad amount");
            }));
        FakeResponse response = exchange("GET", "/x");
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.body()).contains("\"detail\":\"bad amount\"", "\"instance\":\"/custom\"");
    }

    @Test
    void aProblemExceptionWithoutDetailUsesItsTitleAsMessage() {
        ProblemException e = new ProblemException(ProblemDetail.of(402), new BadRequestException("x"));
        assertThat(e.getMessage()).isEqualTo("Payment Required");
        assertThat(e.getStatus()).isEqualTo(402);
        assertThat(e.toProblem().extensions()).isEmpty();
    }
}
