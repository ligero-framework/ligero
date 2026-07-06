package com.ligero.beans;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code static} method as a factory for the optional
 * {@code ligero-processor}: the method's return type becomes a binding whose
 * value is the method's result, letting you contribute beans the processor
 * can't construct itself — third-party types (a {@code DataSource}),
 * configuration objects, anything with custom setup.
 *
 * <pre>{@code
 * @Provides
 * static DataSource dataSource() {
 *     var ds = new HikariDataSource();
 *     ds.setJdbcUrl(System.getenv("DB_URL"));
 *     return ds;
 * }
 * }</pre>
 *
 * <p>Compile-time only (the processor turns it into a plain
 * {@code bind(DataSource.class, b -> EnclosingClass.dataSource())}), so it has
 * {@code SOURCE} retention and no runtime footprint.</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Provides {
}
