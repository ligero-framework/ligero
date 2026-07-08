package com.ligero.spi;

import java.util.Optional;

/**
 * SPI for an external configuration source, discovered via
 * {@link java.util.ServiceLoader}. Keeps the core free of any config-format
 * dependency: {@code ligero-config-yaml} provides a YAML + profiles
 * implementation, but a source could equally read TOML, a remote store, etc.
 *
 * <p>Keys are dotted paths (e.g. {@code server.port}, {@code db.url}). The
 * framework reads its own settings from the canonical {@code server.*} /
 * {@code security.*} keys; application settings are read by the app through
 * {@link com.ligero.config.Config}.</p>
 */
public interface ConfigSource {

    /** Value for {@code key}, if this source defines it. */
    Optional<String> get(String key);

    /** Active profile this source resolved (e.g. {@code "dev"}), if any. */
    default Optional<String> profile() {
        return Optional.empty();
    }

    /** Higher wins when several sources define the same key (default 0). */
    default int priority() {
        return 0;
    }
}
