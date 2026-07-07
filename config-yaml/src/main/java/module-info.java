/**
 * Ligero YAML configuration: a {@link com.ligero.spi.ConfigSource} that loads
 * {@code ligero.yml} and a {@code ligero-<profile>.yml} overlay, with
 * {@code ${ENV:-default}} interpolation.
 */
module com.ligero.config.yaml {
    requires transitive com.ligero.core;
    requires org.yaml.snakeyaml;

    exports com.ligero.config.yaml;

    provides com.ligero.spi.ConfigSource with com.ligero.config.yaml.YamlConfigSource;
}
