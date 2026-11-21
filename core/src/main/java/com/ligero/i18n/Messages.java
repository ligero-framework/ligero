package com.ligero.i18n;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translated messages from classpath resource bundles — plain
 * {@code .properties} files, read as UTF-8:
 *
 * <pre>
 * src/main/resources/messages.properties       greeting=Hello, {0}!
 * src/main/resources/messages_es.properties    greeting=¡Hola, {0}!
 * </pre>
 *
 * <pre>{@code
 * Messages messages = Messages.bundle("messages");
 * app.register(Messages.class, messages)
 *    .use(LocaleMiddleware.supporting(Locale.ENGLISH, Locale.forLanguageTag("es")));
 *
 * app.get("/hello/{name}", ctx -> ctx.text(ctx.message("greeting", ctx.pathParam("name"))));
 * }</pre>
 *
 * <p>Arguments are formatted with {@link MessageFormat} for the locale
 * ({@code {0,number}}, {@code {1,date}}, ...); note that a single quote
 * starts a quoted section there, so write {@code ''} for an apostrophe. A
 * key missing from the locale's file falls back to the base file, and a key
 * missing everywhere renders as the key itself, so a forgotten translation
 * shows up in the page instead of failing the request.</p>
 */
public final class Messages {

    private final String baseName;
    private final ClassLoader loader;
    private final ConcurrentHashMap<Locale, Optional<ResourceBundle>> bundles = new ConcurrentHashMap<>();

    private Messages(String baseName, ClassLoader loader) {
        this.baseName = Objects.requireNonNull(baseName, "baseName");
        this.loader = loader;
    }

    /** Bundle {@code baseName} (e.g. {@code "messages"} or {@code "i18n/messages"}) on the classpath. */
    public static Messages bundle(String baseName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return new Messages(baseName, loader != null ? loader : Messages.class.getClassLoader());
    }

    /** Same, loading from a specific class loader. */
    public static Messages bundle(String baseName, ClassLoader loader) {
        return new Messages(baseName, Objects.requireNonNull(loader, "loader"));
    }

    /** The message for {@code key} in {@code locale}, or the key itself when there is none. */
    public String get(Locale locale, String key, Object... args) {
        return find(locale, key, args).orElse(key);
    }

    /** The message for {@code key} in {@code locale}, if any file defines it. */
    public Optional<String> find(Locale locale, String key, Object... args) {
        Objects.requireNonNull(key, "key");
        Optional<ResourceBundle> bundle = bundles.computeIfAbsent(locale == null ? Locale.ROOT : locale, this::load);
        if (bundle.isEmpty()) {
            return Optional.empty();
        }
        String pattern;
        try {
            pattern = bundle.get().getString(key);
        } catch (MissingResourceException missing) {
            return Optional.empty();
        }
        if (args == null || args.length == 0) {
            return Optional.of(pattern);
        }
        return Optional.of(new MessageFormat(pattern, locale == null ? Locale.ROOT : locale).format(args));
    }

    /** The message for {@code key} in the first of {@code locales} that has it, or the key. */
    public String get(List<Locale> locales, String key, Object... args) {
        for (Locale locale : locales) {
            Optional<String> message = find(locale, key, args);
            if (message.isPresent()) {
                return message.get();
            }
        }
        return key;
    }

    private Optional<ResourceBundle> load(Locale locale) {
        try {
            // no fallback to the JVM's default locale: the base file is the fallback
            return Optional.of(ResourceBundle.getBundle(baseName, locale, loader,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)));
        } catch (MissingResourceException none) {
            return Optional.empty();
        }
    }
}
