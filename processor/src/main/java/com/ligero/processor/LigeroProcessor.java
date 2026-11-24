package com.ligero.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional Ligero DI: reads the stereotype annotations
 * ({@code @Component/@Service/@Repository/@Controller}) and {@code @Provides}
 * static methods at <em>compile time</em> and generates the same explicit
 * {@code bind(...)} wiring you would otherwise write by hand — so there is no
 * classpath scanning and no runtime reflection.
 *
 * <p>For {@code @Controller} classes with typed routes ({@code @Get},
 * {@code @Post}, ... from {@code com.ligero.web}) it also emits a
 * {@code <Controller>Routes} class next to the controller whose
 * {@code mount(app, controller)} registers each method as a plain
 * {@code app.route(...)} lambda: parameters are converted with explicit,
 * type-specific calls chosen here at compile time, and the return value is
 * handed to {@code ctx.respond(...)}. Mistakes (an unknown path parameter, an
 * unsupported type, a private method) are reported as compile errors.</p>
 *
 * <p>Per Java package it emits {@code <pkg>.LigeroGeneratedModule} (a
 * {@link com.ligero.LigeroModule}) with the package's bindings and, for
 * controllers exposing {@code register(Ligero)}, their route mounting. It
 * also emits a single {@code com.ligero.generated.GeneratedModules.all()}
 * returning every generated module, so an app starts with:</p>
 *
 * <pre>{@code Modules.install(app, GeneratedModules.all());}</pre>
 */
@SupportedAnnotationTypes({
    "com.ligero.beans.stereotype.Component",
    "com.ligero.beans.stereotype.Service",
    "com.ligero.beans.stereotype.Repository",
    "com.ligero.beans.stereotype.Controller",
    "com.ligero.beans.Provides",
    "com.ligero.web.Get",
    "com.ligero.web.Post",
    "com.ligero.web.Put",
    "com.ligero.web.Patch",
    "com.ligero.web.Delete",
    "com.ligero.web.Route",
    "com.ligero.web.ClientApi",
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class LigeroProcessor extends AbstractProcessor {

    private static final Set<String> STEREOTYPES = Set.of(
        "com.ligero.beans.stereotype.Component",
        "com.ligero.beans.stereotype.Service",
        "com.ligero.beans.stereotype.Repository",
        "com.ligero.beans.stereotype.Controller");
    private static final String CONTROLLER = "com.ligero.beans.stereotype.Controller";
    private static final String PROVIDES = "com.ligero.beans.Provides";
    private static final String WEB = "com.ligero.web.";
    /** Verb annotation -> HTTP method. {@code @Route} carries its own. */
    private static final Map<String, String> VERBS = verbs();
    private static final String ROUTE = WEB + "Route";
    private static final String API_VERSION = WEB + "ApiVersion";
    private static final String CLIENT_API = WEB + "ClientApi";
    private static final String REST_CLIENT = "com.ligero.client.RestClient";
    private static final String CLIENT_RESPONSE = "com.ligero.client.ClientResponse";
    private static final String VERSION_PATTERN = "[vV]?[0-9]{1,9}(\\.[0-9]{1,9}){0,2}\\+?";
    private static final String CONTEXT = "com.ligero.http.Context";
    private static final String BINDING = "com.ligero.web.Binding";
    private static final String LIGERO = "com.ligero.Ligero";
    private static final String GENERATED_MODULE = "LigeroGeneratedModule";
    private static final String AGGREGATOR = "com.ligero.generated.GeneratedModules";

    /** package name -> its collected bindings, gathered across rounds. */
    private final Map<String, PackageModel> packages = new LinkedHashMap<>();
    /** One entry per controller that declares typed routes. */
    private final List<RoutesModel> routeClasses = new ArrayList<>();

    private static Map<String, String> verbs() {
        Map<String, String> verbs = new LinkedHashMap<>();
        verbs.put(WEB + "Get", "GET");
        verbs.put(WEB + "Post", "POST");
        verbs.put(WEB + "Put", "PUT");
        verbs.put(WEB + "Patch", "PATCH");
        verbs.put(WEB + "Delete", "DELETE");
        return verbs;
    }
    private boolean generated;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (String stereotype : STEREOTYPES) {
            TypeElement annotation = elements().getTypeElement(stereotype);
            if (annotation != null) {
                for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    handleBean((TypeElement) element, stereotype);
                }
            }
        }
        TypeElement provides = elements().getTypeElement(PROVIDES);
        if (provides != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(provides)) {
                handleProvides((ExecutableElement) element);
            }
        }
        TypeElement clientApi = elements().getTypeElement(CLIENT_API);
        if (clientApi != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(clientApi)) {
                generateClient(element);
            }
        }
        reportRoutesOutsideControllers(roundEnv);
        // Generate as soon as the annotated classes are in hand (they all arrive
        // in the first round). Emitting early — not in the processingOver round —
        // lets hand-written code reference the generated GeneratedModules.
        if (!generated && !packages.isEmpty()) {
            generated = true;
            writeSources();
        }
        return false;
    }

    // ---------------------------------------------------------------- clients

    private final Set<String> generatedClients = new java.util.HashSet<>();

    /** Generates {@code <Interface>Client} for an interface annotated with {@code @ClientApi}. */
    private void generateClient(Element element) {
        if (element.getKind() != javax.lang.model.element.ElementKind.INTERFACE) {
            error(element, "@ClientApi goes on an interface; ligero-processor generates its implementation");
            return;
        }
        TypeElement type = (TypeElement) element;
        String qualified = type.getQualifiedName().toString();
        if (!generatedClients.add(qualified)) {
            return;
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, "a @ClientApi interface cannot be generic");
            return;
        }
        String pkg = packageOf(type);
        Object prefixValue = annotationValue(type, CLIENT_API, "value");
        String prefix = prefixValue == null ? "" : prefixValue.toString();
        String simpleName = (pkg.isEmpty() ? qualified : qualified.substring(pkg.length() + 1)).replace('.', '_') + "Client";

        StringBuilder methods = new StringBuilder();
        boolean valid = true;
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.DEFAULT) || modifiers.contains(Modifier.STATIC)
                    || modifiers.contains(Modifier.PRIVATE)) {
                continue; // implemented by the interface itself
            }
            String code = clientMethod(method, prefix);
            if (code == null) {
                valid = false;
            } else {
                methods.append(code);
            }
        }
        if (!valid) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        sb.append("// Generated by ligero-processor from ").append(qualified).append(". Do not edit.\n");
        sb.append("public final class ").append(simpleName).append(" implements ").append(qualified).append(" {\n\n");
        sb.append("    private final ").append(REST_CLIENT).append(" client;\n\n");
        sb.append("    /** Calls go through {@code client}, relative to its base URL. */\n");
        sb.append("    public ").append(simpleName).append("(").append(REST_CLIENT).append(" client) {\n");
        sb.append("        this.client = java.util.Objects.requireNonNull(client, \"client\");\n");
        sb.append("    }\n");
        sb.append(methods);
        sb.append("}\n");
        write(pkg.isEmpty() ? simpleName : pkg + "." + simpleName, sb.toString());
    }

    /** The implementation of one client method, or {@code null} after reporting an error. */
    private String clientMethod(ExecutableElement method, String prefix) {
        String httpMethod = null;
        String subPath = "";
        int verbs = 0;
        for (Map.Entry<String, String> verb : VERBS.entrySet()) {
            if (hasAnnotation(method, verb.getKey())) {
                verbs++;
                httpMethod = verb.getValue();
                Object value = annotationValue(method, verb.getKey(), "value");
                subPath = value == null ? "" : value.toString();
            }
        }
        if (hasAnnotation(method, ROUTE)) {
            verbs++;
            Object value = annotationValue(method, ROUTE, "method");
            httpMethod = value == null ? "" : value.toString().trim().toUpperCase(java.util.Locale.ROOT);
            Object path = annotationValue(method, ROUTE, "path");
            subPath = path == null ? "" : path.toString();
        }
        if (verbs != 1) {
            error(method, verbs == 0
                ? "say which call this is: @Get, @Post, @Put, @Patch, @Delete or @Route"
                : "declares more than one HTTP method annotation");
            return null;
        }
        if (!method.getTypeParameters().isEmpty()) {
            error(method, "client methods cannot be generic");
            return null;
        }
        String fullPath = joinPath(prefix, subPath);
        Map<String, String> pathValues = new LinkedHashMap<>();
        List<String> calls = new ArrayList<>();
        boolean bodySeen = false;
        StringBuilder signature = new StringBuilder();
        int index = 0;
        for (VariableElement param : method.getParameters()) {
            String name = param.getSimpleName().toString();
            String argument = "arg" + index++;
            if (signature.length() > 0) {
                signature.append(", ");
            }
            signature.append(param.asType().toString()).append(' ').append(argument);
            boolean path = hasAnnotation(param, WEB + "PathParam");
            boolean query = hasAnnotation(param, WEB + "QueryParam");
            boolean header = hasAnnotation(param, WEB + "HeaderParam");
            boolean body = hasAnnotation(param, WEB + "Body");
            int sources = (path ? 1 : 0) + (query ? 1 : 0) + (header ? 1 : 0) + (body ? 1 : 0);
            if (sources != 1) {
                error(param, sources == 0
                    ? "say where this parameter goes: @PathParam, @QueryParam, @HeaderParam or @Body"
                    : "pick one of @PathParam, @QueryParam, @HeaderParam or @Body");
                return null;
            }
            if (path) {
                String segment = nameOf(param, WEB + "PathParam", name);
                if (!declaresPathParam(fullPath, segment)) {
                    error(param, "the path " + fullPath + " has no {" + segment + "} segment");
                    return null;
                }
                pathValues.put(segment, argument);
            } else if (query) {
                calls.add(".query(" + literal(nameOf(param, WEB + "QueryParam", name)) + ", " + argument + ")");
            } else if (header) {
                calls.add(".header(" + literal(nameOf(param, WEB + "HeaderParam", name)) + ", " + argument + ")");
            } else {
                if (bodySeen) {
                    error(param, "a client method can send a single @Body");
                    return null;
                }
                bodySeen = true;
                calls.add(".body(" + argument + ")");
            }
        }
        // the placeholders, in the order they appear in the path
        List<String> ordered = new ArrayList<>();
        for (String segment : fullPath.split("/")) {
            if (segment.startsWith("{") && segment.endsWith("}")) {
                String placeholder = segment.substring(1, segment.length() - 1);
                String value = pathValues.get(placeholder);
                if (value == null) {
                    error(method, "no @PathParam for {" + placeholder + "} in " + fullPath);
                    return null;
                }
                ordered.add(value);
            }
        }
        String terminal = clientTerminal(method);
        if (terminal == null) {
            return null;
        }
        TypeMirror returnType = method.getReturnType();
        StringBuilder sb = new StringBuilder("\n    @Override\n    public ");
        sb.append(returnType.toString()).append(' ').append(method.getSimpleName()).append('(').append(signature)
          .append(") {\n");
        sb.append("        ").append(returnType.getKind() == TypeKind.VOID ? "" : "return ")
          .append("client.request(").append(literal(httpMethod)).append(", ").append(literal(fullPath));
        for (String value : ordered) {
            sb.append(", ").append(value);
        }
        sb.append(")");
        for (String call : calls) {
            sb.append("\n            ").append(call);
        }
        sb.append("\n            ").append(terminal).append(";\n    }\n");
        return sb.toString();
    }

    /** How the generated method finishes the request, depending on its return type. */
    private String clientTerminal(ExecutableElement method) {
        TypeMirror type = method.getReturnType();
        switch (type.getKind()) {
            case VOID:
                return ".send()";
            case BOOLEAN:
                return ".retrieve(java.lang.Boolean.class)";
            case INT:
                return ".retrieve(java.lang.Integer.class)";
            case LONG:
                return ".retrieve(java.lang.Long.class)";
            case DOUBLE:
                return ".retrieve(java.lang.Double.class)";
            case ARRAY:
                if (type.toString().equals("byte[]")) {
                    return ".retrieve(byte[].class)";
                }
                error(method, "return a List<T> instead of an array (byte[] is supported)");
                return null;
            case DECLARED:
                break;
            default:
                error(method, "unsupported return type " + type);
                return null;
        }
        DeclaredType declared = (DeclaredType) type;
        String raw = qualifiedOf(type);
        List<? extends TypeMirror> args = declared.getTypeArguments();
        if (raw.equals(CLIENT_RESPONSE)) {
            return ".execute()";
        }
        if (raw.equals("java.util.Optional") || raw.equals("java.util.List") || raw.equals("java.util.Collection")
                || raw.equals("java.util.Set")) {
            if (args.size() != 1 || args.get(0).getKind() != TypeKind.DECLARED
                    || !((DeclaredType) args.get(0)).getTypeArguments().isEmpty()) {
                error(method, raw.substring(raw.lastIndexOf('.') + 1)
                    + " needs a concrete element type, e.g. List<Product> (not List<Map<...>> or List<T>)");
                return null;
            }
            String element = qualifiedOf(args.get(0)) + ".class";
            return switch (raw) {
                case "java.util.Optional" -> ".retrieveOptional(" + element + ")";
                case "java.util.Set" -> ".retrieveList(" + element + ").stream()"
                    + ".collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))";
                default -> ".retrieveList(" + element + ")";
            };
        }
        if (!args.isEmpty()) {
            error(method, "unsupported generic return type " + type
                + " — return a record that wraps it, or ClientResponse");
            return null;
        }
        return ".retrieve(" + raw + ".class)";
    }

    // ---------------------------------------------------------------- collect

    private void handleBean(TypeElement type, String stereotype) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(type, stereotype + " must be a concrete class");
            return;
        }
        String pkg = packageOf(type);
        if (pkg.isEmpty()) {
            error(type, "Ligero's processor needs annotated classes to live in a named package");
            return;
        }
        ExecutableElement ctor = selectConstructor(type);
        if (ctor == null) {
            return; // error already reported
        }
        List<String> params = new ArrayList<>();
        for (VariableElement param : ctor.getParameters()) {
            String name = qualifiedOf(param.asType());
            if (name == null) {
                error(type, "constructor parameter '" + param.getSimpleName()
                    + "' is not an injectable type — provide it with @Provides or an explicit bind()");
                return;
            }
            params.add(name);
        }
        String impl = type.getQualifiedName().toString();
        RoutesModel routes = CONTROLLER.equals(stereotype) ? collectRoutes(type, pkg) : null;
        String key = bindingKey(type, stereotype);
        if (routes != null) {
            // typed routes call the controller's own methods, so it must be
            // bound (and fetched) as its concrete class, not as an interface
            String explicit = asAttribute(type, stereotype);
            if (explicit != null && !explicit.equals("java.lang.Void") && !explicit.equals(impl)) {
                error(type, "a controller with typed routes (@Get, @Post, ...) is bound as its own class — remove as = ...");
                return;
            }
            key = impl;
            routeClasses.add(routes);
        }
        boolean hasRoutes = hasRegisterMethod(type);
        packages.computeIfAbsent(pkg, PackageModel::new)
            .beans.add(new BeanBinding(key, impl, params, hasRoutes,
                routes == null ? null : routes.pkg + "." + routes.simpleName));
    }

    // ---------------------------------------------------------- typed routes

    /** A verb annotation on a method whose class is not a @Controller would be silently dead. */
    private void reportRoutesOutsideControllers(RoundEnvironment roundEnv) {
        List<String> annotations = new ArrayList<>(VERBS.keySet());
        annotations.add(ROUTE);
        for (String name : annotations) {
            TypeElement annotation = elements().getTypeElement(name);
            if (annotation == null) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                Element owner = element.getEnclosingElement();
                if (!hasAnnotation(owner, CONTROLLER) && !hasAnnotation(owner, CLIENT_API)) {
                    error(element, "@" + annotation.getSimpleName() + " only works inside a class annotated with "
                        + "@Controller (com.ligero.beans.stereotype.Controller), or an interface annotated with "
                        + "@ClientApi");
                }
            }
        }
    }

    /** The controller's typed routes, or {@code null} when it declares none (or they are invalid). */
    private RoutesModel collectRoutes(TypeElement type, String pkg) {
        Object prefixValue = annotationValue(type, CONTROLLER, "value");
        String prefix = prefixValue == null ? "" : prefixValue.toString();
        Object classVersionValue = annotationValue(type, API_VERSION, "value");
        String classVersion = classVersionValue == null ? null : classVersionValue.toString().trim();
        if (classVersion != null && !classVersion.matches(VERSION_PATTERN)) {
            error(type, "@ApiVersion must look like \"2\", \"1.1\" or \"2+\"");
            return null;
        }
        List<RouteMethod> routes = new ArrayList<>();
        boolean valid = true;
        boolean usesValidation = false;
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            String httpMethod = null;
            String subPath = "";
            int verbCount = 0;
            for (Map.Entry<String, String> verb : VERBS.entrySet()) {
                if (hasAnnotation(method, verb.getKey())) {
                    verbCount++;
                    httpMethod = verb.getValue();
                    Object value = annotationValue(method, verb.getKey(), "value");
                    subPath = value == null ? "" : value.toString();
                }
            }
            if (hasAnnotation(method, ROUTE)) {
                verbCount++;
                Object value = annotationValue(method, ROUTE, "method");
                httpMethod = value == null ? "" : value.toString().trim().toUpperCase(java.util.Locale.ROOT);
                Object path = annotationValue(method, ROUTE, "path");
                subPath = path == null ? "" : path.toString();
                if (!httpMethod.matches("[A-Z]+")) {
                    error(method, "@Route method must be an HTTP method name such as \"QUERY\"");
                    valid = false;
                    continue;
                }
            }
            if (verbCount == 0) {
                continue;
            }
            if (verbCount > 1) {
                error(method, "declares more than one HTTP method annotation — keep one, or split the method");
                valid = false;
                continue;
            }
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.STATIC)
                    || modifiers.contains(Modifier.ABSTRACT)) {
                error(method, "route methods must be non-private instance methods "
                    + "(the generated routes class lives in the same package and calls them directly)");
                valid = false;
                continue;
            }
            String fullPath = joinPath(prefix, subPath);
            List<String> arguments = new ArrayList<>();
            boolean argumentsOk = true;
            boolean bodySeen = false;
            for (VariableElement param : method.getParameters()) {
                boolean isBody = hasAnnotation(param, WEB + "Body");
                if (isBody && bodySeen) {
                    error(param, "a route method can take a single @Body parameter");
                    argumentsOk = false;
                    continue;
                }
                bodySeen |= isBody;
                String argument = argumentFor(param, fullPath);
                if (argument == null) {
                    argumentsOk = false; // error already reported
                } else {
                    arguments.add(argument);
                    usesValidation |= hasAnnotation(param, WEB + "Valid");
                }
            }
            if (!argumentsOk) {
                valid = false;
                continue;
            }
            Object status = annotationValue(method, WEB + "Status", "value");
            int statusCode = status instanceof Integer code ? code : 0;
            if (status != null && (statusCode < 100 || statusCode > 599)) {
                error(method, "@Status must be a valid HTTP status code (100-599)");
                valid = false;
                continue;
            }
            Object versionValue = annotationValue(method, API_VERSION, "value");
            String version = versionValue == null ? classVersion : versionValue.toString().trim();
            if (version != null && !version.matches(VERSION_PATTERN)) {
                error(method, "@ApiVersion must look like \"2\", \"1.1\" or \"2+\"");
                valid = false;
                continue;
            }
            RouteMethod route = new RouteMethod(httpMethod, fullPath, method.getSimpleName().toString(), arguments,
                method.getReturnType().getKind() == TypeKind.VOID, statusCode, version);
            for (RouteMethod other : routes) {
                if (other.httpMethod.equals(route.httpMethod) && other.path.equals(route.path)
                        && java.util.Objects.equals(canonicalVersion(other.apiVersion), canonicalVersion(route.apiVersion))) {
                    error(method, route.apiVersion == null
                        ? "another method already serves " + route.httpMethod + " " + route.path
                            + " — give the methods different @ApiVersion values"
                        : "another method already serves " + route.httpMethod + " " + route.path
                            + " for API version " + route.apiVersion);
                    valid = false;
                }
            }
            routes.add(route);
        }
        if (!valid || routes.isEmpty()) {
            return null;
        }
        String impl = type.getQualifiedName().toString();
        // Outer.Inner -> Outer_InnerRoutes (a flat, top-level class in the same package)
        String simpleName = impl.substring(pkg.length() + 1).replace('.', '_') + "Routes";
        return new RoutesModel(pkg, simpleName, impl, routes, usesValidation);
    }

    /** The Java expression that produces one argument of a route method, or {@code null} after an error. */
    private String argumentFor(VariableElement param, String fullPath) {
        TypeMirror type = param.asType();
        boolean path = hasAnnotation(param, WEB + "PathParam");
        boolean query = hasAnnotation(param, WEB + "QueryParam");
        boolean header = hasAnnotation(param, WEB + "HeaderParam");
        boolean body = hasAnnotation(param, WEB + "Body");
        boolean validated = hasAnnotation(param, WEB + "Valid");
        int sources = (path ? 1 : 0) + (query ? 1 : 0) + (header ? 1 : 0) + (body ? 1 : 0);
        if (sources > 1) {
            error(param, "pick one of @PathParam, @QueryParam, @HeaderParam or @Body");
            return null;
        }
        if (validated && !body) {
            error(param, "@Valid applies to the @Body parameter");
            return null;
        }
        if (sources == 0) {
            if (CONTEXT.equals(qualifiedOf(type))) {
                return "ctx";
            }
            error(param, "say where this parameter comes from: @PathParam, @QueryParam, @HeaderParam or @Body "
                + "(or declare it as com.ligero.http.Context)");
            return null;
        }
        if (body) {
            return bodyArgument(param, type, validated);
        }
        String javaName = param.getSimpleName().toString();
        if (path) {
            String name = nameOf(param, WEB + "PathParam", javaName);
            if (!declaresPathParam(fullPath, name)) {
                error(param, "the route " + fullPath + " has no {" + name + "} segment");
                return null;
            }
            String kind = "path parameter";
            String raw = BINDING + ".required(ctx.pathParam(" + literal(name) + "), "
                + literal(kind) + ", " + literal(name) + ")";
            return convert(param, type, raw, kind, name);
        }
        String annotation = WEB + (query ? "QueryParam" : "HeaderParam");
        String name = nameOf(param, annotation, javaName);
        String kind = query ? "query parameter" : "header";
        if (query && isListOfString(type)) {
            return "ctx.queryParams(" + literal(name) + ")";
        }
        String raw = (query ? "ctx.queryParam(" : "ctx.header(") + literal(name) + ")";
        Object defaultValue = annotationValue(param, annotation, "defaultValue");
        if (defaultValue != null && !defaultValue.toString().isEmpty()) {
            raw = BINDING + ".orDefault(" + raw + ", " + literal(defaultValue.toString()) + ")";
        } else if (type.getKind().isPrimitive()) {
            // a primitive cannot say "absent", so the parameter is required
            raw = BINDING + ".required(" + raw + ", " + literal(kind) + ", " + literal(name) + ")";
        }
        return convert(param, type, raw, kind, name);
    }

    private String bodyArgument(VariableElement param, TypeMirror type, boolean validated) {
        String expression;
        if (type.getKind() == TypeKind.ARRAY && type.toString().equals("byte[]")) {
            expression = "ctx.bodyAsBytes()";
        } else if ("java.lang.String".equals(qualifiedOf(type))) {
            expression = "ctx.bodyAsString()";
        } else if (type.getKind() == TypeKind.DECLARED && ((DeclaredType) type).getTypeArguments().isEmpty()) {
            expression = "ctx.body(" + qualifiedOf(type) + ".class)";
        } else {
            error(param, "@Body needs a concrete class or record (wrap generic payloads such as List<T> in a record)");
            return null;
        }
        return validated ? BINDING + ".valid(" + expression + ")" : expression;
    }

    /** Picks the converter for the parameter's declared type — at compile time, no runtime lookup. */
    private String convert(VariableElement param, TypeMirror type, String raw, String kind, String name) {
        String tail = "(" + raw + ", " + literal(kind) + ", " + literal(name) + ")";
        switch (type.getKind()) {
            case INT:
                return BINDING + ".toInt" + tail;
            case LONG:
                return BINDING + ".toLong" + tail;
            case DOUBLE:
                return BINDING + ".toDouble" + tail;
            case BOOLEAN:
                return BINDING + ".toBoolean" + tail;
            case DECLARED:
                break;
            default:
                error(param, "unsupported parameter type " + type + " — use String, int, long, double, boolean, "
                    + "UUID, BigDecimal or an enum");
                return null;
        }
        String qualified = qualifiedOf(type);
        Element element = ((DeclaredType) type).asElement();
        if (qualified == null) {
            error(param, "unsupported parameter type " + type);
            return null;
        }
        switch (qualified) {
            case "java.lang.String":
                return raw;
            case "java.lang.Integer":
                return BINDING + ".toInt" + tail;
            case "java.lang.Long":
                return BINDING + ".toLong" + tail;
            case "java.lang.Double":
                return BINDING + ".toDouble" + tail;
            case "java.lang.Boolean":
                return BINDING + ".toBoolean" + tail;
            case "java.util.UUID":
                return BINDING + ".toUuid" + tail;
            case "java.math.BigDecimal":
                return BINDING + ".toBigDecimal" + tail;
            default:
                if (element.getKind() == javax.lang.model.element.ElementKind.ENUM) {
                    return BINDING + ".toEnum(" + qualified + ".values(), " + raw + ", "
                        + literal(kind) + ", " + literal(name) + ")";
                }
                error(param, "unsupported parameter type " + type + " — use String, int, long, double, boolean, "
                    + "UUID, BigDecimal or an enum");
                return null;
        }
    }

    private boolean isListOfString(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED || !"java.util.List".equals(qualifiedOf(type))) {
            return false;
        }
        List<? extends TypeMirror> arguments = ((DeclaredType) type).getTypeArguments();
        return arguments.size() == 1 && "java.lang.String".equals(qualifiedOf(arguments.get(0)));
    }

    private String nameOf(VariableElement param, String annotation, String fallback) {
        Object value = annotationValue(param, annotation, "value");
        return value == null || value.toString().isEmpty() ? fallback : value.toString();
    }

    /** True when the route pattern has a {@code {name}} or trailing {@code *name} segment. */
    static boolean declaresPathParam(String path, String name) {
        for (String segment : path.split("/")) {
            if (segment.equals("{" + name + "}") || segment.equals("*" + name)) {
                return true;
            }
        }
        return false;
    }

    /** Controller prefix + method path, normalized like the router does. */
    static String joinPath(String prefix, String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : (prefix + "/" + path).split("/")) {
            if (!segment.isEmpty()) {
                sb.append('/').append(segment);
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /** A Java string literal for {@code value}. */
    static String literal(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private Object annotationValue(Element element, String annotationName, String attribute) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().equals(annotationName)) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                    : mirror.getElementValues().entrySet()) {
                if (e.getKey().getSimpleName().contentEquals(attribute)) {
                    return e.getValue().getValue();
                }
            }
        }
        return null;
    }

    private void handleProvides(ExecutableElement method) {
        if (!method.getModifiers().contains(Modifier.STATIC)) {
            error(method, "@Provides methods must be static");
            return;
        }
        String returnType = qualifiedOf(method.getReturnType());
        if (returnType == null) {
            error(method, "@Provides method must return an injectable type");
            return;
        }
        TypeElement enclosing = (TypeElement) method.getEnclosingElement();
        String pkg = packageOf(enclosing);
        packages.computeIfAbsent(pkg, PackageModel::new).provides.add(new ProvidesBinding(
            returnType, enclosing.getQualifiedName().toString(), method.getSimpleName().toString()));
    }

    /** as() override, else the single implemented interface, else the class itself. */
    private String bindingKey(TypeElement type, String stereotype) {
        String explicit = asAttribute(type, stereotype);
        if (explicit != null && !explicit.equals("java.lang.Void")) {
            return explicit;
        }
        List<? extends TypeMirror> interfaces = type.getInterfaces();
        if (interfaces.size() == 1) {
            String only = qualifiedOf(interfaces.get(0));
            if (only != null) {
                return only;
            }
        }
        return type.getQualifiedName().toString();
    }

    private String asAttribute(TypeElement type, String stereotype) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().equals(stereotype)) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                    : mirror.getElementValues().entrySet()) {
                if (e.getKey().getSimpleName().contentEquals("as")) {
                    return qualifiedOf((TypeMirror) e.getValue().getValue());
                }
            }
        }
        return null;
    }

    private ExecutableElement selectConstructor(TypeElement type) {
        List<ExecutableElement> ctors = ElementFilter.constructorsIn(type.getEnclosedElements());
        if (ctors.size() == 1) {
            return ctors.get(0);
        }
        List<ExecutableElement> injected = new ArrayList<>();
        for (ExecutableElement ctor : ctors) {
            if (hasAnnotation(ctor, "com.ligero.beans.Inject")) {
                injected.add(ctor);
            }
        }
        if (injected.size() == 1) {
            return injected.get(0);
        }
        error(type, "has " + ctors.size() + " constructors — annotate one with @Inject "
            + "(com.ligero.beans.Inject) or use an explicit bind()");
        return null;
    }

    private boolean hasRegisterMethod(TypeElement type) {
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals("register")
                    && method.getParameters().size() == 1
                    && LIGERO.equals(qualifiedOf(method.getParameters().get(0).asType()))) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- emit

    private void writeSources() {
        List<String> moduleClasses = new ArrayList<>();
        for (PackageModel model : packages.values()) {
            if (model.beans.isEmpty() && model.provides.isEmpty()) {
                continue;
            }
            String fqcn = model.pkg + "." + GENERATED_MODULE;
            writeModule(fqcn, model);
            moduleClasses.add(fqcn);
        }
        for (RoutesModel routes : routeClasses) {
            writeRoutes(routes);
        }
        writeAggregator(moduleClasses);
    }

    private void writeRoutes(RoutesModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(model.pkg).append(";\n\n");
        sb.append("// Generated by ligero-processor from the typed routes of ").append(model.controller)
          .append(". Do not edit.\n");
        sb.append("public final class ").append(model.simpleName).append(" {\n\n");
        sb.append("    private ").append(model.simpleName).append("() {\n    }\n\n");
        sb.append("    /** Registers every typed route of the controller on the app. */\n");
        sb.append("    public static void mount(com.ligero.Ligero app, ").append(model.controller)
          .append(" controller) {\n");
        if (model.usesValidation) {
            sb.append("        ").append(BINDING).append(".requireValidator(); // fail at startup, not on the first request\n");
        }
        // routes sharing a verb and path but not a version become one dispatching handler
        Map<String, List<RouteMethod>> byRoute = new LinkedHashMap<>();
        for (RouteMethod route : model.routes) {
            byRoute.computeIfAbsent(route.httpMethod + " " + route.path, key -> new ArrayList<>()).add(route);
        }
        for (List<RouteMethod> group : byRoute.values()) {
            RouteMethod first = group.get(0);
            boolean versioned = group.stream().anyMatch(route -> route.apiVersion != null);
            sb.append("        app.route(").append(literal(first.httpMethod)).append(", ")
              .append(literal(first.path)).append(", ");
            if (!versioned) {
                appendHandler(sb, first, "        ");
                sb.append(");\n");
                continue;
            }
            sb.append("com.ligero.versioning.ApiVersioning.handlers()\n");
            for (RouteMethod route : group) {
                if (route.apiVersion == null) {
                    sb.append("            .otherwise(");
                } else {
                    sb.append("            .version(").append(literal(route.apiVersion)).append(", ");
                }
                appendHandler(sb, route, "            ");
                sb.append(")\n");
            }
            sb.append("            .build());\n");
        }
        sb.append("    }\n");
        sb.append("}\n");
        write(model.pkg + "." + model.simpleName, sb.toString());
    }

    private void appendHandler(StringBuilder sb, RouteMethod route, String indent) {
        sb.append("ctx -> {\n");
        String call = "controller." + route.methodName + "(" + joinArguments(route.arguments) + ")";
        if (route.returnsVoid) {
            sb.append(indent).append("    ").append(call).append(";\n");
        } else {
            sb.append(indent).append("    Object result = ").append(call).append(";\n");
        }
        if (route.status != 0) {
            sb.append(indent).append("    if (!ctx.res().isCommitted()) {\n");
            sb.append(indent).append("        ctx.status(").append(route.status).append(");\n");
            sb.append(indent).append("    }\n");
        }
        sb.append(indent).append("    ctx.respond(").append(route.returnsVoid ? "null" : "result").append(");\n");
        sb.append(indent).append("}");
    }

    /** "v2" and "2.0" name the same version as "2"; the "+" suffix is kept. */
    private static String canonicalVersion(String version) {
        if (version == null) {
            return null;
        }
        boolean andLater = version.endsWith("+");
        String number = (andLater ? version.substring(0, version.length() - 1) : version).replaceFirst("^[vV]", "");
        String[] parts = (number + ".0.0").split("\\.");
        return Integer.parseInt(parts[0]) + "." + Integer.parseInt(parts[1]) + "." + Integer.parseInt(parts[2])
            + (andLater ? "+" : "");
    }

    private static String joinArguments(List<String> arguments) {
        if (arguments.isEmpty()) {
            return "";
        }
        if (arguments.size() == 1) {
            return arguments.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arguments.size(); i++) {
            sb.append(i == 0 ? "\n                " : ",\n                ").append(arguments.get(i));
        }
        return sb.toString();
    }

    private void writeModule(String fqcn, PackageModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(model.pkg).append(";\n\n");
        sb.append("// Generated by ligero-processor. Do not edit.\n");
        sb.append("public final class ").append(GENERATED_MODULE)
          .append(" implements com.ligero.LigeroModule {\n\n");

        sb.append("    @Override\n");
        sb.append("    public void beans(com.ligero.beans.Beans.Builder builder) {\n");
        for (ProvidesBinding provide : model.provides) {
            sb.append("        builder.bind(").append(provide.returnType).append(".class, b -> ")
              .append(provide.enclosing).append('.').append(provide.method).append("());\n");
        }
        for (BeanBinding bean : model.beans) {
            sb.append("        builder.bind(").append(bean.key).append(".class, b -> new ")
              .append(bean.impl).append('(');
            for (int i = 0; i < bean.params.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("b.get(").append(bean.params.get(i)).append(".class)");
            }
            sb.append("));\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public void routes(com.ligero.Ligero app, com.ligero.beans.Beans beans) {\n");
        for (BeanBinding bean : model.beans) {
            if (bean.hasRoutes) {
                sb.append("        beans.get(").append(bean.key).append(".class).register(app);\n");
            }
            if (bean.routesClass != null) {
                sb.append("        ").append(bean.routesClass).append(".mount(app, beans.get(")
                  .append(bean.key).append(".class));\n");
            }
        }
        sb.append("    }\n");
        sb.append("}\n");

        write(fqcn, sb.toString());
    }

    private void writeAggregator(List<String> moduleClasses) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.ligero.generated;\n\n");
        sb.append("// Generated by ligero-processor. Do not edit.\n");
        sb.append("public final class GeneratedModules {\n\n");
        sb.append("    private GeneratedModules() {\n    }\n\n");
        sb.append("    /** Every module generated from your annotated classes, in one array. */\n");
        sb.append("    public static com.ligero.LigeroModule[] all() {\n");
        sb.append("        return new com.ligero.LigeroModule[] {\n");
        for (String moduleClass : moduleClasses) {
            sb.append("            new ").append(moduleClass).append("(),\n");
        }
        sb.append("        };\n");
        sb.append("    }\n");
        sb.append("}\n");
        write(AGGREGATOR, sb.toString());
    }

    private void write(String fqcn, String source) {
        try (Writer writer = processingEnv.getFiler().createSourceFile(fqcn).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "ligero-processor could not write " + fqcn + ": " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- helpers

    private String qualifiedOf(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            Element element = ((DeclaredType) type).asElement();
            if (element instanceof TypeElement typeElement) {
                return typeElement.getQualifiedName().toString();
            }
        }
        return null;
    }

    private String packageOf(TypeElement type) {
        return elements().getPackageOf(type).getQualifiedName().toString();
    }

    private boolean hasAnnotation(Element element, String annotationName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private javax.lang.model.util.Elements elements() {
        return processingEnv.getElementUtils();
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
            "@" + element.getSimpleName() + ": " + message, element);
    }

    // ----------------------------------------------------------------- models

    private static final class PackageModel {
        final String pkg;
        final List<BeanBinding> beans = new ArrayList<>();
        final List<ProvidesBinding> provides = new ArrayList<>();

        PackageModel(String pkg) {
            this.pkg = pkg;
        }
    }

    private record BeanBinding(String key, String impl, List<String> params, boolean hasRoutes,
                               String routesClass) {
    }

    private record RoutesModel(String pkg, String simpleName, String controller, List<RouteMethod> routes,
                               boolean usesValidation) {
    }

    private record RouteMethod(String httpMethod, String path, String methodName, List<String> arguments,
                               boolean returnsVoid, int status, String apiVersion) {
    }

    private record ProvidesBinding(String returnType, String enclosing, String method) {
    }
}
