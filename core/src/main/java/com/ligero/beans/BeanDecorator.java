package com.ligero.beans;

/**
 * Instrumentation hook: invoked once per bean right after its factory
 * creates it, before it is cached. Devtools uses this to wrap
 * interface-typed beans with recording proxies in dev profile; production
 * containers simply have no decorator.
 */
@FunctionalInterface
public interface BeanDecorator {

    /** Returns the instance to cache — either {@code bean} or a wrapper of it. */
    <T> T decorate(Class<T> type, T bean);
}
