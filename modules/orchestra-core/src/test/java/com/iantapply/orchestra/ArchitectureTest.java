package com.iantapply.orchestra;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

final class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.iantapply.orchestra");

    @Test
    void apiAndDomainDoNotDependOnImplementationLayers() {
        noClasses()
                .that()
                .resideInAnyPackage("..api..", "..domain..", "..port..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..adapter..", "..engine..", "..metrics..", "..platform..", "..schedule..", "..web..")
                .check(CLASSES);
    }

    @Test
    void engineDoesNotReachIntoAdaptersOrPlatforms() {
        noClasses()
                .that()
                .resideInAPackage("..engine..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..platform..", "..web..")
                .check(CLASSES);
    }
}
