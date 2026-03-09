plugins {
    jacoco
}

jacoco {
    toolVersion = "0.8.14"
}

interface CoverageExtension {
    val exceptions: MapProperty<String, Map<String, Double>>
}

val extension = extensions.create<CoverageExtension>("coverageConfig")
extension.exceptions.convention(emptyMap())

/*
 * Configure test tasks
 */
tasks.withType<Test>().configureEach {
    finalizedBy(tasks.withType<JacocoReport>())

    extensions.configure<JacocoTaskExtension> {
        excludes = listOf(
            "java.*",
            "javax.*",
            "sun.*",
            "jdk.*",
            "com.sun.*",
            "org.w3c.*",
            "org.xml.*"
        )
    }
}

/*
 * Safe JaCoCo report configuration
 */
tasks.withType<JacocoReport>().configureEach {

    dependsOn(tasks.withType<Test>())

    onlyIf {
        executionData.files.any { it.exists() }
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

/*
 * Coverage verification
 */
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {

    dependsOn(tasks.withType<JacocoReport>())

    onlyIf {
        executionData.files.any { it.exists() }
    }

    violationRules {
        val MIN_FLOOR = 0.80.toBigDecimal()
        val TARGET_DEFAULT = 1.00.toBigDecimal()
        val counters = listOf("LINE", "BRANCH", "CLASS", "INSTRUCTION", "METHOD")

        val fileCoverageRules = extension.exceptions.get()

        // 1. Global Rule (100% for everything else)
        rule {
            element = "CLASS"
            excludes = fileCoverageRules.keys.toList()
            counters.forEach { metric ->
                limit {
                    counter = metric
                    value = "COVEREDRATIO"
                    minimum = TARGET_DEFAULT
                }
            }
        }

        // 2. Exception Rules
        fileCoverageRules.forEach { (filePattern, thresholds) ->
            rule {
                element = "CLASS"
                includes = listOf(filePattern)

                counters.forEach { metric ->
                    val override = thresholds[metric]
                    val effectiveRatio = override?.toBigDecimal() ?: TARGET_DEFAULT

                    if (effectiveRatio < MIN_FLOOR) {
                        logger.warn("🚨 [Coverage] $filePattern: $metric ($effectiveRatio) is below 80% floor!")
                    }

                    limit {
                        counter = metric
                        value = "COVEREDRATIO"
                        minimum = effectiveRatio
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
