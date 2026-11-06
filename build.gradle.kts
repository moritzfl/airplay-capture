plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.vaadin)
    alias(libs.plugins.spotless)
}

group = "de.moritzf"
version = "1.0.3-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.vaadin.spring.boot.starter)
    implementation(libs.spring.boot.security)
    implementation(libs.spring.boot.jackson)
    implementation(libs.jmdns)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        prettier(
            mapOf(
                "prettier" to libs.versions.prettier.core.get(),
                "prettier-plugin-java" to libs.versions.prettier.java.get(),
            ),
        ).configFile(".prettierrc.json")
    }
}

vaadin {
    productionMode = true
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "1g"
    // Test classes are independent (temp folders, ephemeral ports): run them in parallel JVMs.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

// Makes the generated Vaadin client bundle part of the normal runtime classpath, so plain Java
// launches (and IntelliJ run configurations) work without building the jar first.
val copyVaadinBundle = tasks.register<Copy>("copyVaadinBundle") {
    dependsOn("vaadinBuildFrontend")
    mustRunAfter("processResources")
    from(layout.buildDirectory.dir("vaadin-build-frontend"))
    into(layout.buildDirectory.dir("resources/main"))
}

tasks.named("classes") {
    dependsOn(copyVaadinBundle)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    dependsOn("vaadinBuildFrontend")
    // the bundle is on the runtime resources and also injected by the Vaadin plugin
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn("vaadinBuildFrontend")
}
