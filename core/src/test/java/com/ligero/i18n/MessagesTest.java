package com.ligero.i18n;

import com.ligero.http.Context;
import com.ligero.middleware.LocaleMiddleware;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagesTest {

    private static final Locale ES = Locale.forLanguageTag("es");
    private final Messages messages = Messages.bundle("i18n/messages");

    @Test
    void translatesAndFormatsForTheLocale() {
        assertThat(messages.get(Locale.ENGLISH, "greeting", "Ana")).isEqualTo("Hello, Ana!");
        assertThat(messages.get(ES, "greeting", "Ana")).isEqualTo("¡Hola, Ana!");
        assertThat(messages.get(ES, "items", 1234)).isEqualTo("Tienes 1.234 artículos");
        assertThat(messages.get(Locale.ENGLISH, "items", 1234)).isEqualTo("You have 1,234 items");
    }

    @Test
    void fallsBackToTheBaseFileThenToTheKey() {
        assertThat(messages.get(ES, "only.base")).isEqualTo("Base only");
        assertThat(messages.get(Locale.JAPANESE, "greeting", "Ana")).isEqualTo("Hello, Ana!");
        assertThat(messages.get(ES, "nope")).isEqualTo("nope");
        assertThat(messages.find(ES, "nope")).isEmpty();
        assertThat(messages.get(List.of(Locale.JAPANESE, ES), "greeting", "Ana")).isEqualTo("Hello, Ana!");
        assertThat(messages.get(List.of(), "greeting")).isEqualTo("greeting");
        assertThat(messages.get((Locale) null, "only.base")).isEqualTo("Base only");
    }

    @Test
    void aMissingBundleRendersKeys() {
        Messages none = Messages.bundle("does/not/exist", getClass().getClassLoader());
        assertThat(none.get(ES, "greeting")).isEqualTo("greeting");
    }

    private static Context context(FakeRequest request) {
        return new Context(request, new FakeResponse(), "/", null, null,
            Map.of(Messages.class, Messages.bundle("i18n/messages")));
    }

    private static Locale chosen(LocaleMiddleware middleware, FakeRequest request) throws Exception {
        Context ctx = context(request);
        middleware.handle(ctx, () -> { });
        return ctx.locale();
    }

    @Test
    void negotiatesTheAcceptLanguageHeader() throws Exception {
        LocaleMiddleware locales = LocaleMiddleware.supporting(Locale.ENGLISH, ES, Locale.forLanguageTag("pt-BR"));

        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Accept-Language", "es-MX,es;q=0.9,en;q=0.5")))
            .isEqualTo(ES);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Accept-Language", "pt"))) // pt -> pt-BR
            .isEqualTo(Locale.forLanguageTag("pt-BR"));
        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Accept-Language", "de, fr;q=0.8")))
            .isEqualTo(Locale.ENGLISH);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Accept-Language", ";;;=bad")))
            .isEqualTo(Locale.ENGLISH);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Accept-Language", "es;q=0, en")))
            .isEqualTo(Locale.ENGLISH);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Accept-Language", "es," + "x".repeat(2000))))
            .isEqualTo(Locale.ENGLISH);
        assertThat(chosen(locales, FakeRequest.of("GET", "/"))).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void anExplicitChoiceWinsAndUnsupportedOnesAreIgnored() throws Exception {
        LocaleMiddleware locales = LocaleMiddleware.builder()
            .supported(Locale.ENGLISH, ES).queryParam("lang").cookie("lang").build();

        assertThat(chosen(locales, FakeRequest.of("GET", "/").queryParam("lang", "es")
            .header("Accept-Language", "en"))).isEqualTo(ES);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").queryParam("lang", "es-AR"))).isEqualTo(ES);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").queryParam("lang", "xx")
            .header("Accept-Language", "es"))).isEqualTo(ES);
        assertThat(chosen(locales, FakeRequest.of("GET", "/").header("Cookie", "lang=es"))).isEqualTo(ES);
    }

    @Test
    void announcesTheChosenLanguage() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/").header("Accept-Language", "es"), response, "/", null, null);
        LocaleMiddleware.supporting(Locale.ENGLISH, ES).handle(ctx, () -> { });
        assertThat(response.headerValue("Content-Language")).isEqualTo("es");
        assertThat(response.headerValue("Vary")).isEqualTo("Accept-Language");
    }

    @Test
    void contextLocaleAndMessageWithoutTheMiddleware() {
        Context ctx = context(FakeRequest.of("GET", "/").header("Accept-Language", "*, es;q=0.5"));
        assertThat(ctx.locale()).isEqualTo(ES);
        assertThat(ctx.message("greeting", "Ana")).isEqualTo("¡Hola, Ana!");
        assertThat(context(FakeRequest.of("GET", "/")).locale()).isEqualTo(Locale.getDefault());
        assertThat(context(FakeRequest.of("GET", "/").header("Accept-Language", "q=;;")).locale())
            .isEqualTo(Locale.getDefault());
    }

    @Test
    void needsAtLeastOneLocale() {
        assertThatThrownBy(() -> LocaleMiddleware.builder().build()).isInstanceOf(IllegalStateException.class);
    }
}
