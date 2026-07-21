package com.ligero.openapi;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSchemaTest {

    record NewUser(String name, int age) {
    }

    enum Role { ADMIN, USER }

    record User(long id, String name, boolean active, List<Role> roles, NewUser original) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatesComponentSchemasAndRefsFromRecords() {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.post("/users", ctx -> { });

        Map<String, Object> doc = OpenApi.of(app, "Demo", "1.0.0")
            .describe("POST", "/users", op -> op
                .summary("Create a user")
                .requestBody(NewUser.class)
                .response(201, User.class))
            .document();

        // components.schemas holds both the body and (transitively) nested records
        Map<String, Object> schemas =
            (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        assertThat(schemas).containsKeys("NewUser", "User");

        // the operation references them
        Map<String, Object> post = (Map<String, Object>)
            ((Map<String, Object>) ((Map<String, Object>) doc.get("paths")).get("/users")).get("post");
        assertThat(post).containsEntry("summary", "Create a user");

        String reqRef = ref(post, "requestBody");
        assertThat(reqRef).isEqualTo("#/components/schemas/NewUser");

        Map<String, Object> responses = (Map<String, Object>) post.get("responses");
        assertThat(responses).containsKey("201");
        assertThat(ref((Map<String, Object>) responses.get("201"), null))
            .isEqualTo("#/components/schemas/User");

        // scalar/typing coverage on the User schema
        Map<String, Object> userProps =
            (Map<String, Object>) ((Map<String, Object>) schemas.get("User")).get("properties");
        assertThat(((Map<String, Object>) userProps.get("id")).get("type")).isEqualTo("integer");
        assertThat(((Map<String, Object>) userProps.get("active")).get("type")).isEqualTo("boolean");
        assertThat(((Map<String, Object>) userProps.get("roles")).get("type")).isEqualTo("array");
        // nested record -> $ref
        assertThat(((Map<String, Object>) userProps.get("original")).get("$ref"))
            .isEqualTo("#/components/schemas/NewUser");
    }

    record Rich(double price, java.math.BigDecimal total, java.time.LocalDate day,
                java.time.Instant at, String[] tags, java.util.Set<String> labels, Object blob) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsScalarsTemporalsArraysAndCollections() {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());

        Map<String, Object> doc = OpenApi.of(app, "Demo", "1.0.0").model(Rich.class).document();
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>)
            ((Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas"))
                .get("Rich")).get("properties");

        assertThat(((Map<String, Object>) props.get("price")).get("type")).isEqualTo("number");
        assertThat(((Map<String, Object>) props.get("total")).get("type")).isEqualTo("number");
        assertThat((Map<String, Object>) props.get("day"))
            .containsEntry("type", "string").containsEntry("format", "date");
        assertThat((Map<String, Object>) props.get("at"))
            .containsEntry("type", "string").containsEntry("format", "date-time");
        assertThat(((Map<String, Object>) props.get("tags")).get("type")).isEqualTo("array");
        assertThat(((Map<String, Object>) props.get("labels")).get("type")).isEqualTo("array");
        assertThat(((Map<String, Object>) props.get("blob")).get("type")).isEqualTo("object");
    }

    @SuppressWarnings("unchecked")
    private static String ref(Map<String, Object> node, String underKey) {
        Map<String, Object> container = underKey == null ? node : (Map<String, Object>) node.get(underKey);
        Map<String, Object> content = (Map<String, Object>) container.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        return (String) schema.get("$ref");
    }
}
