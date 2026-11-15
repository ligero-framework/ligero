/** Ligero Brotli compression: a {@code ContentEncoder} for the {@code br} content coding. */
module com.ligero.compress.brotli {
    requires com.ligero.core;
    requires com.aayushatharva.brotli4j;

    exports com.ligero.compress.brotli;

    provides com.ligero.spi.ContentEncoder with com.ligero.compress.brotli.BrotliContentEncoder;
}
