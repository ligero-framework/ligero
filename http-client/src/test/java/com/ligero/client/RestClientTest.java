package com.ligero.client;

import com.ligero.http.NotFoundException;
import com.ligero.http.ProblemDetail;
import com.ligero.http.ProblemException;
import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientTest {

    public record Product(long id, String name, double price) {
    }

    public record NewProduct(String name, double price) {
    }

    private static LigeroTest server;
    private static LigeroTest other;
    private static RestClient client;

    @BeforeAll
    static void start() {
        Map<Long, Product> store = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        store.put(1L, new Product(1, "Keyboard", 49.9));
        ids.set(1);
        other = LigeroTest.create(app -> app
            .get("/echo-auth", ctx -> ctx.text(String.valueOf(ctx.header("Authorization")))));
        server = LigeroTest.create(app -> app
            .get("/products", ctx -> ctx.json(store.values().stream()
                .filter(p -> ctx.queryParam("name") == null || p.name().equals(ctx.queryParam("name")))
                .toList()))
            .get("/products/{id}", ctx -> {
                Product product = store.get(ctx.pathParamAsLong("id"));
                if (product == null) {
                    throw new NotFoundException("No product " + ctx.pathParam("id"));
                }
                ctx.json(product);
            })
            .post("/products", ctx -> {
                NewProduct body = ctx.body(NewProduct.class);
                long id = ids.incrementAndGet();
                Product created = new Product(id, body.name(), body.price());
                store.put(id, created);
                ctx.status(201).json(created);
            })
            .delete("/products/{id}", ctx -> {
                store.remove(ctx.pathParamAsLong("id"));
                ctx.status(204);
            })
            .get("/raw/{segment}", ctx -> ctx.text(ctx.req().getUri()))
            .get("/query", ctx -> ctx.text(ctx.req().getUri()))
            .get("/headers", ctx -> ctx.text(ctx.header("X-Tenant") + "|" + ctx.header("API-Version")
                + "|" + ctx.header("User-Agent")))
            .post("/form", ctx -> ctx.text(ctx.formParam("q") + "|" + ctx.formParam("lang")))
            .post("/text", ctx -> ctx.text(ctx.header("Content-Type") + "|" + ctx.bodyAsString()))
            .get("/conflict", ctx -> {
                throw new ProblemException(ProblemDetail.of(409).type("https://example.com/probs/stock")
                    .title("Out of stock").detail("Item 7 is gone").with("item", 7));
            })
            .get("/plain-error", ctx -> ctx.status(503).text("down"))
            .get("/big", ctx -> ctx.text("x".repeat(4096)))
            .get("/slow", ctx -> {
                Thread.sleep(2000);
                ctx.text("late");
            })
            .get("/redirect", ctx -> ctx.redirect("/products/1", 302))
            .post("/redirect-post", ctx -> ctx.redirect("/products", 303))
            .get("/loop", ctx -> ctx.redirect("/loop", 302))
            .get("/away", ctx -> ctx.redirect(other.baseUrl() + "/echo-auth", 302))
            .get("/to-file", ctx -> ctx.redirect("file:///etc/passwd", 302))
            .get("/not-json", ctx -> ctx.json("just a string").status(200))
            .get("/no-content", ctx -> ctx.status(204)));
        client = RestClient.builder().baseUrl(server.baseUrl()).timeout(Duration.ofSeconds(1)).build();
    }

    @AfterAll
    static void stop() {
        server.close();
        other.close();
    }

    @Test
    void readsAndWritesJson() {
        assertThat(client.get("/products/{id}", 1).retrieve(Product.class))
            .isEqualTo(new Product(1, "Keyboard", 49.9));

        Product created = client.post("/products").body(new NewProduct("Mouse", 25)).retrieve(Product.class);
        assertThat(created.name()).isEqualTo("Mouse");
        assertThat(client.get("/products").query("name", "Mouse").retrieveList(Product.class))
            .containsExactly(created);
        client.delete("/products/{id}", created.id()).send();
        assertThat(client.get("/products/{id}", created.id()).retrieveOptional(Product.class)).isEmpty();
        assertThat(client.get("/products/{id}", 1).retrieveOptional(Product.class)).isPresent();
    }

    @Test
    void errorStatusesCarryTheProblem() {
        assertThatThrownBy(() -> client.get("/conflict").retrieve(Product.class))
            .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                assertThat(e.status()).isEqualTo(409);
                ProblemDetail problem = e.problem().orElseThrow();
                assertThat(problem.title()).isEqualTo("Out of stock");
                assertThat(problem.type()).isEqualTo("https://example.com/probs/stock");
                assertThat(problem.detail()).isEqualTo("Item 7 is gone");
                assertThat(problem.instance()).isEqualTo("/conflict");
                assertThat(problem.extensions()).containsEntry("item", 7);
                assertThat(e.getMessage()).contains("GET").contains("/conflict").contains("409");
            });
        assertThatThrownBy(() -> client.get("/plain-error").send())
            .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                assertThat(e.status()).isEqualTo(503);
                assertThat(e.problem()).isEmpty();
                assertThat(e.response().bodyAsString()).isEqualTo("down");
            });
        ClientResponse raw = client.get("/plain-error").execute();
        assertThat(raw.status()).isEqualTo(503);
        assertThat(raw.isSuccess()).isFalse();
        assertThat(raw.toString()).endsWith("-> 503");
    }

    @Test
    void pathValuesAreOneEncodedSegment() {
        assertThat(client.get("/raw/{segment}", "../admin?x=1 y").retrieveString())
            .isEqualTo("/raw/..%2Fadmin%3Fx%3D1%20y");
        assertThat(client.get("/query").query("q", "a&b=c").query("tag", List.of("x", "y"))
            .query("skip", null).query("opt", java.util.Optional.of("z")).retrieveString())
            .isEqualTo("/query?q=a%26b%3Dc&tag=x&tag=y&opt=z");
        assertThatThrownBy(() -> client.get("/products/{id}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get("/products", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get("/products/{id", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get("/products/{id}", (Object) null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultAndPerRequestHeaders() {
        RestClient tenant = RestClient.builder().baseUrl(server.baseUrl())
            .header("X-Tenant", "acme").apiVersion("API-Version", "2").build();
        assertThat(tenant.get("/headers").retrieveString()).isEqualTo("acme|2|ligero-http-client");
        assertThat(tenant.get("/headers").header("X-Tenant", "other").header("User-Agent", "me")
            .header("Ignored", null).retrieveString()).isEqualTo("other|2|me");
    }

    @Test
    void formsTextAndBytes() {
        assertThat(client.post("/form").form(Map.of("q", "a b&c")).retrieveString()).isEqualTo("a b&c|null");
        assertThat(client.post("/text").body("hola").retrieveString()).isEqualTo("text/plain; charset=utf-8|hola");
        assertThat(client.post("/text").json("{\"a\":1}").retrieveString()).isEqualTo("application/json|{\"a\":1}");
        assertThat(client.post("/text").body(new byte[] {104, 105}).retrieveString())
            .isEqualTo("application/octet-stream|hi");
        assertThat(client.get("/products/{id}", 1).retrieve(byte[].class)).isNotEmpty();
    }

    @Test
    void emptyAndMalformedBodies() {
        assertThat(client.get("/no-content").retrieve(Product.class)).isNull();
        assertThat(client.get("/no-content").retrieveList(Product.class)).isEmpty();
        assertThatThrownBy(() -> client.get("/not-json").retrieve(Product.class))
            .isInstanceOf(RestClientException.class).hasMessageContaining("not a valid Product");
    }

    @Test
    void followsRedirectsWithinBounds() {
        assertThat(client.get("/redirect").retrieve(Product.class).name()).isEqualTo("Keyboard");
        // 303 after a POST becomes a GET of the list
        assertThat(client.post("/redirect-post").body(new NewProduct("x", 1)).retrieveList(Product.class))
            .isNotEmpty();
        assertThatThrownBy(() -> client.get("/loop").retrieve(String.class))
            .isInstanceOfSatisfying(HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(302));
        assertThatThrownBy(() -> client.get("/to-file").retrieve(String.class))
            .isInstanceOf(RestClientException.class).hasMessageContaining("Only http and https");
        RestClient noRedirects = RestClient.builder().baseUrl(server.baseUrl()).maxRedirects(0).build();
        assertThat(noRedirects.get("/redirect").execute().status()).isEqualTo(302);
    }

    @Test
    void credentialsDoNotFollowARedirectToAnotherHost() {
        // same host, other port: a different origin
        RestClient authed = RestClient.builder().baseUrl(server.baseUrl()).header("Authorization", "Bearer s3cret").build();
        assertThat(authed.get("/away").retrieveString()).isEqualTo("null");
    }

    @Test
    void boundsResponsesAndTime() {
        RestClient small = RestClient.builder().baseUrl(server.baseUrl()).maxResponseBytes(100).build();
        assertThatThrownBy(() -> small.get("/big").retrieveString())
            .isInstanceOf(RestClientException.class).hasMessageContaining("100");
        assertThat(small.get("/products/{id}", 1).retrieve(Product.class)).isNotNull();
        assertThatThrownBy(() -> client.get("/slow").timeout(Duration.ofMillis(200)).retrieveString())
            .isInstanceOf(RestClientException.class).hasMessageContaining("timed out");
    }

    @Test
    void connectionFailuresAreRestClientExceptions() {
        RestClient nowhere = RestClient.builder().baseUrl("http://127.0.0.1:1").connectTimeout(Duration.ofMillis(500)).build();
        assertThatThrownBy(() -> nowhere.get("/x").send()).isInstanceOf(RestClientException.class);
        assertThatThrownBy(() -> RestClient.create().get("/relative").send()).isInstanceOf(IllegalArgumentException.class);
        RestClient absolute = RestClient.create();
        assertThat(absolute.get(server.baseUrl() + "/products/{id}", 1).retrieve(Product.class)).isNotNull();
    }

    @Test
    void thePublicOnlyFilterRefusesInternalAddresses() {
        RestClient webhooks = RestClient.builder().addressFilter(AddressFilter.publicOnly()).build();
        assertThatThrownBy(() -> webhooks.get(server.baseUrl() + "/products").send())
            .isInstanceOfSatisfying(BlockedAddressException.class, e -> {
                assertThat(e.address()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
                assertThat(e.host()).isIn("localhost", "127.0.0.1");
            });
        RestClient allowed = RestClient.builder().addressFilter(AddressFilter.publicOnly().allow("127.0.0.0/8"))
            .build();
        assertThat(allowed.get(server.baseUrl() + "/products/{id}", 1).retrieve(Product.class)).isNotNull();
        RestClient denied = RestClient.builder()
            .addressFilter(AddressFilter.allowAll().deny("127.0.0.0/8")).build();
        assertThatThrownBy(() -> denied.get(server.baseUrl() + "/products").send())
            .isInstanceOf(BlockedAddressException.class);
        assertThatThrownBy(() -> webhooks.get("http://no-such-host.invalid/").send())
            .isInstanceOf(RestClientException.class).hasMessageContaining("Unknown host");
    }

    @Test
    void addressFilterClassifiesAddresses() throws Exception {
        AddressFilter filter = AddressFilter.publicOnly();
        for (String internal : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fd00::1", "::ffff:10.0.0.1", "::ffff:169.254.169.254",
            "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1", "::10.0.0.1"}) {
            assertThat(filter.permits(InetAddress.getByName(internal))).as(internal).isFalse();
        }
        for (String external : new String[] {"8.8.8.8", "1.1.1.1", "172.32.0.1", "2606:4700:4700::1111",
            "::ffff:8.8.8.8"}) {
            assertThat(filter.permits(InetAddress.getByName(external))).as(external).isTrue();
        }
        assertThat(AddressFilter.allowAll().permits(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(filter.allow("10.0.0.0/8").deny("10.0.0.5").permits(InetAddress.getByName("10.0.0.5"))).isFalse();
        assertThat(filter.allow("10.0.0.0/8").permits(InetAddress.getByName("10.9.9.9"))).isTrue();
        assertThatThrownBy(() -> filter.allow("not-an-ip")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter.deny("10.0.0.0/40")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter.deny("10.0.0.0/x")).isInstanceOf(IllegalArgumentException.class);
        filter.check("[::ffff:8.8.8.8]");
        assertThatThrownBy(() -> filter.check("[::1]")).isInstanceOf(BlockedAddressException.class);
    }

    @Test
    void builderRejectsUnsafeSettings() {
        assertThatThrownBy(() -> RestClient.builder().baseUrl("ftp://x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestClient.builder().baseUrl("/relative")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestClient.builder().baseUrl("http://x/?q=1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestClient.builder().maxRedirects(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestClient.builder().maxResponseBytes(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestClient.builder().httpClient(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build())).isInstanceOf(IllegalArgumentException.class);
        RestClient custom = RestClient.builder().baseUrl(server.baseUrl() + "/")
            .httpClient(HttpClient.newHttpClient()).build();
        assertThat(custom.get("products/{id}", 1).retrieve(Product.class)).isNotNull();
    }

    @Test
    void responseAccessors() {
        ClientResponse response = client.get("/products/{id}", 1).execute();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.header("content-type")).startsWith("application/json");
        assertThat(response.headers()).containsKey("Content-Type");
        assertThat(response.header("X-None")).isNull();
        assertThat(response.as(String.class)).contains("Keyboard");
        assertThat(response.as(Void.class)).isNull();
        assertThat(response.request()).startsWith("GET http://");
    }
}
