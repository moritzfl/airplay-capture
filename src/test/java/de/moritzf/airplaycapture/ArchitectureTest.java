package de.moritzf.airplaycapture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Package boundaries. The classes in the root package (Settings, Log, AppDirs, UiState, and the
 * wiring in Main and AppLifecycle) are shared by every package and not part of these rules.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("de.moritzf.airplaycapture");

    /** Receiving, recording and converting work without the web UI and without Spring. */
    @Test
    void coreDoesNotDependOnUiWebOrFrameworks() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "de.moritzf.airplaycapture.raop..",
                "de.moritzf.airplaycapture.recording..",
                "de.moritzf.airplaycapture.media..",
                "de.moritzf.airplaycapture.library..",
                "de.moritzf.airplaycapture.util.."
            )
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "de.moritzf.airplaycapture.ui..",
                "de.moritzf.airplaycapture.web..",
                "de.moritzf.airplaycapture.config..",
                "com.vaadin..",
                "org.springframework.."
            )
            .check(CLASSES);
    }

    @Test
    void packagesHaveNoCycles() {
        slices()
            .matching("de.moritzf.airplaycapture.(*)..")
            .should()
            .beFreeOfCycles()
            .check(CLASSES);
    }
}
