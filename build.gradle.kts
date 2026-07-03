plugins {
    base
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "com.infenia.yukta"
    version = "0.0.1-SNAPSHOT"

    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("trivy") {
    description = "Run Trivy vulnerability scanning on all modules"
    group = "verification"

    doFirst {
        logger.quiet("")
        logger.quiet("╔════════════════════════════════════════════════════════════════╗")
        logger.quiet("║          Trivy Vulnerability Scanner - All Modules             ║")
        logger.quiet("╚════════════════════════════════════════════════════════════════╝")
    }

    doLast {
        logger.quiet("")
        logger.quiet("✓ Trivy scanning complete across all modules")
        logger.quiet("")
    }
}

// Wire up dependencies after task is registered (configuration cache compatible)
afterEvaluate {
    subprojects.forEach { subproject ->
        subproject.tasks.findByName("trivy")?.let { trivyTask ->
            rootProject.tasks.named("trivy").configure {
                dependsOn(trivyTask)
            }
        }
    }
}
