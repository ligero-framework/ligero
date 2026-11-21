package com.ligero.middleware;

import com.ligero.http.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Chooses the locale of each request among the ones the application
 * supports, from (in order) an optional query parameter, an optional cookie,
 * and the {@code Accept-Language} header, falling back to the first supported
 * locale. Handlers read it with {@code ctx.locale()}; the response says which
 * one was used ({@code Content-Language}) and that it varies with
 * {@code Accept-Language}, so caches keep the translations apart.
 *
 * <pre>{@code
 * app.use(LocaleMiddleware.builder()
 *     .supported(Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.forLanguageTag("pt-BR"))
 *     .queryParam("lang")     // ?lang=es wins over the header
 *     .cookie("lang")
 *     .build());
 * }</pre>
 */
public final class LocaleMiddleware implements Middleware {

    /** Context attribute holding the chosen {@link Locale}. */
    public static final String LOCALE_ATTRIBUTE = "ligero.locale";

    /** Longer Accept-Language headers are ignored rather than parsed. */
    private static final int MAX_HEADER_LENGTH = 1024;

    private final List<Locale> supported;
    private final String queryParam;
    private final String cookie;

    private LocaleMiddleware(Builder builder) {
        this.supported = List.copyOf(builder.supported);
        this.queryParam = builder.queryParam;
        this.cookie = builder.cookie;
    }

    /** Negotiates among {@code supported}; the first one is the default. */
    public static LocaleMiddleware supporting(Locale... supported) {
        return builder().supported(supported).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        Locale locale = resolve(ctx);
        ctx.attribute(LOCALE_ATTRIBUTE, locale);
        ctx.header("Content-Language", locale.toLanguageTag());
        ctx.vary("Accept-Language");
        chain.proceed();
    }

    /** The locale this middleware would choose for {@code ctx}. */
    public Locale resolve(Context ctx) {
        if (queryParam != null) {
            Locale explicit = match(ctx.queryParam(queryParam));
            if (explicit != null) {
                return explicit;
            }
        }
        if (cookie != null) {
            Locale explicit = match(ctx.cookie(cookie));
            if (explicit != null) {
                return explicit;
            }
        }
        Locale negotiated = negotiate(ctx.header("Accept-Language"));
        return negotiated != null ? negotiated : supported.get(0);
    }

    /** An explicit choice (query parameter, cookie): a supported tag, or its language. */
    private Locale match(String tag) {
        if (tag == null || tag.isBlank() || tag.length() > 35) {
            return null;
        }
        Locale wanted = Locale.forLanguageTag(tag.trim());
        for (Locale locale : supported) {
            if (locale.toLanguageTag().equalsIgnoreCase(wanted.toLanguageTag())) {
                return locale;
            }
        }
        for (Locale locale : supported) {
            if (!wanted.getLanguage().isEmpty() && locale.getLanguage().equals(wanted.getLanguage())) {
                return locale;
            }
        }
        return null;
    }

    private Locale negotiate(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank() || acceptLanguage.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        List<Locale.LanguageRange> wanted;
        try {
            wanted = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        Locale exact = Locale.lookup(wanted, supported);
        if (exact != null) {
            return exact;
        }
        // "es-MX" asked, only "es" offered -> lookup finds it; "es" asked, only
        // "es-ES" offered -> fall back to the first supported locale with that language
        for (Locale.LanguageRange range : wanted) {
            if (range.getWeight() == 0) {
                continue;
            }
            String language = Locale.forLanguageTag(range.getRange()).getLanguage();
            for (Locale locale : supported) {
                if (!language.isEmpty() && locale.getLanguage().equals(language)) {
                    return locale;
                }
            }
        }
        return null;
    }

    /** Builder; at least one supported locale is required. */
    public static final class Builder {
        private final List<Locale> supported = new ArrayList<>();
        private String queryParam;
        private String cookie;

        /** Locales the application offers; the first is the default. */
        public Builder supported(Locale... locales) {
            for (Locale locale : locales) {
                supported.add(Objects.requireNonNull(locale, "locale"));
            }
            return this;
        }

        /** Lets {@code ?<name>=es} choose the locale. */
        public Builder queryParam(String name) {
            this.queryParam = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Lets a cookie choose the locale (e.g. one set by a language picker). */
        public Builder cookie(String name) {
            this.cookie = Objects.requireNonNull(name, "name");
            return this;
        }

        public LocaleMiddleware build() {
            if (supported.isEmpty()) {
                throw new IllegalStateException("At least one supported locale is required");
            }
            return new LocaleMiddleware(this);
        }
    }
}
