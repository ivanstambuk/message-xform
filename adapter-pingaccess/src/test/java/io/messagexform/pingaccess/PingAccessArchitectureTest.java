package io.messagexform.pingaccess;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture guardrails for the PingAccess adapter module.
 *
 * <p>These rules are intentionally narrow and enforce the highest-risk boundaries:
 * no reflection, no core internal-package dependency, and no accidental mutable
 * instance state in {@link PingAccessAdapter}.
 */
@AnalyzeClasses(
        packages = "io.messagexform.pingaccess",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class PingAccessArchitectureTest {

    @ArchTest
    static final ArchRule noCoreInternalDependencies = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.messagexform.core..internal..")
            .because("adapter modules must not depend on core internal implementation details");

    @ArchTest
    static final ArchRule noCrossAdapterDependencies = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.messagexform.standalone..", "io.messagexform.pinggateway..")
            .because("adapter-pingaccess must remain isolated from other adapter modules");

    @ArchTest
    static final ArchRule noReflectionUsage = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.lang.reflect..")
            .because("reflection is forbidden by project governance");

    @ArchTest
    static final ArchRule pingAccessAdapterHasOnlyFinalFields = classes()
            .that()
            .haveSimpleName("PingAccessAdapter")
            .should()
            .haveOnlyFinalFields()
            .because("request parse state must not be stored in mutable shared fields");
}
