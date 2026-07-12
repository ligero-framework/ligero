package com.ligero.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable version of the "Architecture rules" listed in CONTRIBUTING.md.
 * Until now those rules were only enforced by human review; these ArchUnit
 * fitness functions fail the build the moment a change breaks one of Ligero's
 * core promises (dependency-free core, reflection-free wiring, SPI boundaries).
 *
 * <p>Only {@code ligero-core} is on this module's classpath, so
 * {@code com.ligero..} resolves to core classes exclusively — the rules do not
 * accidentally judge adapter modules (which legitimately depend on Jackson,
 * Jetty, …).</p>
 */
class CoreArchitectureTest {

    private static JavaClasses core;

    @BeforeAll
    static void importCore() {
        core = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ligero");
    }

    /** Rule #1 — the core carries no third-party runtime dependency but slf4j-api. */
    @Test
    void coreDependsOnlyOnJdkAndSlf4j() {
        noClasses().that().resideInAPackage("com.ligero..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("com.ligero..", "java..", "javax..", "org.slf4j..")
            .because("ligero-core must stay dependency-free except slf4j-api; "
                + "integrations live in adapter modules (architecture rule #1)")
            .check(core);
    }

    /** Rule #5 (promise) — Ligero wires apps without runtime reflection. */
    @Test
    void coreUsesNoRuntimeReflection() {
        noClasses().that().resideInAPackage("com.ligero..")
            .should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..")
            .because("the DI container and router are reflection-free, so apps build "
                + "cleanly to a GraalVM native image")
            .check(core);
    }

    /** Rule #3 — infrastructure sits behind an SPI interface in com.ligero.spi. */
    @Test
    void serviceProviderInterfacesAreInterfacesInTheSpiPackage() {
        classes().that().haveSimpleName("ServerEngine")
            .or().haveSimpleName("BodyMapper")
            .or().haveSimpleName("TemplateEngine")
            .or().haveSimpleName("MetricsCollector")
            .should().beInterfaces()
            .andShould().resideInAPackage("com.ligero.spi..")
            .because("infrastructure is resolved through a ServiceLoader SPI "
                + "(architecture rule #3)")
            .check(core);
    }

    /** Rule #4 — path normalization/matching lives only in the router package. */
    @Test
    void pathNormalizationStaysInTheRouterPackage() {
        classes().that().haveSimpleName("PathNormalizer")
            .or().haveSimpleName("RouteTrie")
            .should().resideInAPackage("com.ligero.router..")
            .because("path normalization/matching has a single home (architecture rule #4)")
            .check(core);
    }

    /** The core must not depend on the JDK's internal HTTP server; that is the
     *  ligero-server-jdk adapter's job (keeps the SPI boundary honest). */
    @Test
    void coreDoesNotReferenceTheJdkHttpServer() {
        noClasses().that().resideInAPackage("com.ligero..")
            .should().dependOnClassesThat().resideInAnyPackage("com.sun..")
            .because("the JDK HTTP server belongs to the ligero-server-jdk adapter, "
                + "not the engine-agnostic core")
            .check(core);
    }
}
