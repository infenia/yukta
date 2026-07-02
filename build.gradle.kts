plugins {
    base
    alias(libs.plugins.spotless) apply false
    // CycloneDX disabled - incompatible with dependency locking in Gradle 9.0
    // alias(libs.plugins.cyclonedx)
}

allprojects {
    group = "com.infenia.yukta"
    version = "0.0.1-SNAPSHOT"

    dependencyLocking {
        lockAllConfigurations()
    }
}
