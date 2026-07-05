package com.ligero.http;

import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultipartTest {

    private static final String BOUNDARY = "----ligero";

    private static String multipartBody() {
        return "--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"username\"\r\n\r\n"
            + "ada\r\n"
            + "--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"tags\"\r\n\r\n"
            + "a\r\n"
            + "--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"tags\"\r\n\r\n"
            + "b\r\n"
            + "--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"upload\"; filename=\"notes.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\n"
            + "file content here\r\n"
            + "--" + BOUNDARY + "--\r\n";
    }

    @Test
    void parsesFieldsAndFiles() {
        Multipart multipart = Multipart.parse(
            multipartBody().getBytes(StandardCharsets.UTF_8),
            "multipart/form-data; boundary=" + BOUNDARY);

        assertThat(multipart.field("username")).isEqualTo("ada");
        assertThat(multipart.fields().get("tags")).containsExactly("a", "b");
        Multipart.UploadedFile file = multipart.file("upload");
        assertThat(file.filename()).isEqualTo("notes.txt");
        assertThat(file.contentType()).isEqualTo("text/plain");
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo("file content here");
    }

    @Test
    void contextExposesMultipartAndFormParams() {
        FakeRequest request = FakeRequest.of("POST", "/upload")
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .body(multipartBody());
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);

        assertThat(ctx.multipart().files()).hasSize(1);
        assertThat(ctx.formParam("username")).isEqualTo("ada");
    }

    @Test
    void nonMultipartRequestFailsWith400() {
        Context ctx = new Context(FakeRequest.of("POST", "/"), new FakeResponse(), "/", null, null);
        assertThatThrownBy(ctx::multipart).isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingBoundaryFailsWith400() {
        assertThatThrownBy(() -> Multipart.parse("x".getBytes(StandardCharsets.UTF_8), "multipart/form-data"))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void quotedBoundaryIsSupported() {
        assertThat(Multipart.extractBoundary("multipart/form-data; boundary=\"abc\"")).isEqualTo("abc");
    }
}
