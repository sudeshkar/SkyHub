package com.skyhub.booking;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.skyhub.booking", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_has_no_spring_dependencies = noClasses()
            .that().resideInAPackage("com.skyhub.booking.domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("com.skyhub.booking.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.skyhub.booking.application..",
                    "com.skyhub.booking.web..",
                    "com.skyhub.booking.persistence..",
                    "com.skyhub.booking.config..",
                    "com.skyhub.booking.api..");

    /** Entities never cross the API boundary: the web layer sees only views and generated DTOs. */
    @ArchTest
    static final ArchRule web_never_touches_entities = noClasses()
            .that().resideInAPackage("com.skyhub.booking.web..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class);

    @ArchTest
    static final ArchRule web_does_not_access_persistence = noClasses()
            .that().resideInAPackage("com.skyhub.booking.web..")
            .should().dependOnClassesThat().resideInAPackage("com.skyhub.booking.persistence..");

    @ArchTest
    static final ArchRule application_exposes_no_api_dtos = noClasses()
            .that().resideInAPackage("com.skyhub.booking.application..")
            .should().dependOnClassesThat().resideInAPackage("com.skyhub.booking.api..");

    /** Use cases know a ManageTokenIssuer port, never the JWT library behind it (ADR-0011). */
    @ArchTest
    static final ArchRule application_does_not_know_the_token_format = noClasses()
            .that().resideInAPackage("com.skyhub.booking.application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.security..", "com.nimbusds..");

    @ArchTest
    static final ArchRule domain_does_not_know_security = noClasses()
            .that().resideInAPackage("com.skyhub.booking.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.skyhub.booking.security..", "org.springframework.security..");

    @ArchTest
    static final ArchRule controllers_live_in_web = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("com.skyhub.booking.web..");
}
