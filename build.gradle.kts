plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.vaadin)
}

group = "de.moritzf"
version = "2.0.2-SNAPSHOT"

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
    // The plain library, checked from JUnit tests: archunit-junit5's engine targets JUnit 5.
    testImplementation(libs.archunit)
    testImplementation(libs.spring.test)
    testRuntimeOnly(libs.junit.launcher)
}

val formatterDir = file("tools/format")
val installFormatter = tasks.register<Exec>("installFormatter") {
    workingDir(formatterDir)
    inputs.files("tools/format/package.json", "tools/format/package-lock.json")
    outputs.dir("tools/format/node_modules")
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm", "ci", "--ignore-scripts")
}
for ((name, mode) in mapOf("spotlessApply" to "--write", "spotlessCheck" to "--check")) {
    tasks.register<Exec>(name) {
        dependsOn(installFormatter)
        workingDir(formatterDir)
        commandLine("node", "node_modules/prettier/bin/prettier.cjs", mode, "../../src/*/java/**/*.java")
    }
}
tasks.check { dependsOn("spotlessCheck") }

vaadin {
    productionMode = true
}

// The running application reads its own version from this for update checks. The
// build time is left out so an unchanged build stays up to date.
springBoot {
    buildInfo {
        excludes.set(setOf("time"))
    }
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
