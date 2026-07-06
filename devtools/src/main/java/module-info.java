/**
 * Ligero devtools: development-time dashboard served at {@code /ligero/dev}
 * with the bean dependency graph and live per-request traces through the
 * layers (controller -&gt; service -&gt; repository). Development profile only —
 * never put this module on a production classpath.
 */
module com.ligero.devtools {
    requires transitive com.ligero.core;

    exports com.ligero.devtools;
}
