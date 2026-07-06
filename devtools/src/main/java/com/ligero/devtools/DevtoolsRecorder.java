package com.ligero.devtools;

import com.ligero.beans.BeanDecorator;
import com.ligero.beans.stereotype.Component;
import com.ligero.beans.stereotype.Controller;
import com.ligero.beans.stereotype.Repository;
import com.ligero.beans.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link BeanDecorator} that wraps interface-typed beans in a
 * {@link Proxy JDK dynamic proxy} recording every call — method, arguments,
 * return value preview and elapsed time — into the {@link RequestTrace} of
 * the request being handled (if any; outside a request the proxy is a
 * pass-through).
 *
 * <p>Beans bound as concrete classes cannot be proxied without bytecode
 * generation, so they stay unwrapped; the dashboard lists them so it is
 * obvious why their calls don't show up in traces.</p>
 */
final class DevtoolsRecorder implements BeanDecorator {

    private static final int PREVIEW_MAX = 240;

    /** Trace of the request currently handled by this thread (one request = one thread). */
    static final ThreadLocal<RequestTrace> CURRENT = new ThreadLocal<>();

    /** Binding type name -> stereotype of the real implementation class. */
    private final Map<String, String> stereotypes = new ConcurrentHashMap<>();

    /** Concrete-class bindings we could not spy (shown as a dashboard notice). */
    private final List<String> unspied = new CopyOnWriteArrayList<>();

    @Override
    public <T> T decorate(Class<T> type, T bean) {
        String stereotype = stereotypeOf(bean.getClass());
        stereotypes.put(type.getName(), stereotype);
        if (!type.isInterface()) {
            unspied.add(type.getName());
            return bean;
        }
        String beanName = bean.getClass().getSimpleName();
        String declaredBy = type.getSimpleName();
        Object proxy = Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, method, args) -> spy(bean, beanName, declaredBy, stereotype, method, args));
        return type.cast(proxy);
    }

    private Object spy(Object bean, String beanName, String declaredBy, String stereotype,
                       Method method, Object[] args) throws Throwable {
        RequestTrace trace = CURRENT.get();
        if (trace == null || method.getDeclaringClass() == Object.class) {
            return invoke(bean, method, args);
        }
        RequestTrace.Call call =
            trace.enter(beanName, declaredBy, stereotype, method.getName(), preview(args));
        long start = System.nanoTime();
        try {
            Object result = invoke(bean, method, args);
            call.complete(preview(result), null, (System.nanoTime() - start) / 1_000);
            return result;
        } catch (Throwable e) {
            call.complete(null, e.getClass().getSimpleName() + ": " + e.getMessage(),
                          (System.nanoTime() - start) / 1_000);
            throw e;
        } finally {
            trace.exit();
        }
    }

    private static Object invoke(Object bean, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    Map<String, String> stereotypes() {
        return Map.copyOf(stereotypes);
    }

    List<String> unspied() {
        return List.copyOf(unspied);
    }

    static String preview(Object value) {
        if (value == null) {
            return "null";
        }
        String text;
        if (value instanceof Object[] array) {
            StringBuilder sb = new StringBuilder();
            for (Object item : array) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(item);
            }
            text = sb.toString();
        } else {
            text = String.valueOf(value);
        }
        return text.length() <= PREVIEW_MAX ? text : text.substring(0, PREVIEW_MAX) + "…";
    }

    private static String stereotypeOf(Class<?> type) {
        if (type.isAnnotationPresent(Controller.class)) {
            return "controller";
        }
        if (type.isAnnotationPresent(Service.class)) {
            return "service";
        }
        if (type.isAnnotationPresent(Repository.class)) {
            return "repository";
        }
        if (type.isAnnotationPresent(Component.class)) {
            return "component";
        }
        return "bean";
    }
}
