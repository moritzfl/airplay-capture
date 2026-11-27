pluginManagement {
    repositories {
        // Renovate refreshes checksums from shared runners that Maven Central rate-limits.
        if (gradle.startParameter.writeDependencyVerifications.isNotEmpty()) {
            maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                // The Plugin Portal can publish different bytes under the same plugin coordinates.
                content {
                    excludeModuleByRegex(".*", ".*\\.gradle\\.plugin")
                    excludeModuleByRegex(".*", ".*-gradle-plugin")
                }
            }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "airplay-capture"
