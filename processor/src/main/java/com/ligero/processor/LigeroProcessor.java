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
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class LigeroProcessor extends AbstractProcessor {

    private static final Set<String> STEREOTYPES = Set.of(
        "com.ligero.beans.stereotype.Component",
        "com.ligero.beans.stereotype.Service",
        "com.ligero.beans.stereotype.Repository",
        "com.ligero.beans.stereotype.Controller");
    private static final String PROVIDES = "com.ligero.beans.Provides";
    private static final String LIGERO = "com.ligero.Ligero";
    private static final String GENERATED_MODULE = "LigeroGeneratedModule";
    private static final String AGGREGATOR = "com.ligero.generated.GeneratedModules";

    /** package name -> its collected bindings, gathered across rounds. */
    private final Map<String, PackageModel> packages = new LinkedHashMap<>();
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
        // Generate as soon as the annotated classes are in hand (they all arrive
        // in the first round). Emitting early — not in the processingOver round —
        // lets hand-written code reference the generated GeneratedModules.
        if (!generated && !packages.isEmpty()) {
            generated = true;
            writeSources();
        }
        return false;
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
        String key = bindingKey(type, stereotype);
        String impl = type.getQualifiedName().toString();
        boolean hasRoutes = hasRegisterMethod(type);
        packages.computeIfAbsent(pkg, PackageModel::new)
            .beans.add(new BeanBinding(key, impl, params, hasRoutes));
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
        writeAggregator(moduleClasses);
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

    private record BeanBinding(String key, String impl, List<String> params, boolean hasRoutes) {
    }

    private record ProvidesBinding(String returnType, String enclosing, String method) {
    }
}
