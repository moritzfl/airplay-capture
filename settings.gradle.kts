pluginManagement {
    repositories {
        // Renovate refreshes checksums from shared runners that Maven Central rate-limits.
        if (gradle.startParameter.writeDependencyVerifications.isNotEmpty()) {
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        gradlePluginPortal()
    }
}

rootProject.name = "airplay-capture"
