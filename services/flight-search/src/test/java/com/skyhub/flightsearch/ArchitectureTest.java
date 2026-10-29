package com.skyhub.flightsearch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.skyhub.flightsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_has_no_spring_dependencies = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.skyhub.flightsearch.application..",
                    "com.skyhub.flightsearch.web..",
                    "com.skyhub.flightsearch.persistence..",
                    "com.skyhub.flightsearch.config..",
                    "com.skyhub.flightsearch.api..");

    @ArchTest
    static final ArchRule web_does_not_access_persistence = noClasses()
            .that().resideInAPackage("com.skyhub.flightsearch.web..")
            .should().dependOnClassesThat().resideInAPackage("com.skyhub.flightsearch.persistence..");

    /** Entities never cross the API boundary: the web layer sees only domain values and generated DTOs. */
    @ArchTest
    static final ArchRule web_never_touches_entities = noClasses()
            .that().resideInAPackage("com.skyhub.flightsearch.web..")
            .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class);

    @ArchTest
    static final ArchRule controllers_live_in_web = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..web..");
}
