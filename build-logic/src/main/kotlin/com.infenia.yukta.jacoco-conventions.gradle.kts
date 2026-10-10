// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

plugins {
    jacoco
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

abstract class CoverageExtension {
    /** Class-name pattern -> {LINE|BRANCH -> minimum ratio}. Other counters are ignored. */
    abstract val exceptions: MapProperty<String, Map<String, Double>>

    /** Class-name pattern -> why it is allowed below the floor. Required when a ratio is < 0.80. */
    abstract val reasons: MapProperty<String, String>

    fun exception(pattern: String, reason: String, thresholds: Map<String, Double>) {
        exceptions.put(pattern, thresholds)
        reasons.put(pattern, reason)
    }
}

val extension = extensions.create<CoverageExtension>("coverageConfig")
extension.exceptions.convention(emptyMap())
extension.reasons.convention(emptyMap())

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
val minFloor = 0.80.toBigDecimal()
// Only LINE and BRANCH are enforced; CLASS/INSTRUCTION/METHOD mostly duplicate them.
val defaults = mapOf("LINE" to 0.90.toBigDecimal(), "BRANCH" to 0.85.toBigDecimal())

fun globToRegex(glob: String) =
    Regex(glob.split("*").joinToString(".*") { part -> part.split("?").joinToString(".") { Regex.escape(it) } })

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {

    dependsOn(tasks.withType<JacocoReport>())

    onlyIf {
        executionData.files.any { it.exists() }
    }

    val patterns = extension.exceptions.get()
    val reasons = extension.reasons.get()
    val projectPath = project.path

    violationRules {
        // 1. Global rule for everything not listed as an exception
        rule {
            element = "CLASS"
            excludes = patterns.keys.toList()
            defaults.forEach { (metric, ratio) ->
                limit {
                    counter = metric
                    value = "COVEREDRATIO"
                    minimum = ratio
                }
            }
        }

        // 2. Exception rules
        patterns.forEach { (filePattern, thresholds) ->
            rule {
                element = "CLASS"
                includes = listOf(filePattern)

                defaults.forEach { (metric, default) ->
                    val effective = thresholds[metric]?.toBigDecimal() ?: default
                    if (effective < minFloor && reasons[filePattern].isNullOrBlank()) {
                        throw GradleException(
                            "$projectPath: coverage exception '$filePattern' sets $metric to $effective " +
                                "(< $minFloor) without a reason; use coverageConfig.exception(pattern, reason, thresholds)"
                        )
                    }
                    limit {
                        counter = metric
                        value = "COVEREDRATIO"
                        minimum = effective
                    }
                }
            }
        }
    }

    // Stale-entry check: every exception pattern must match a compiled class.
    doFirst {
        val classNames = classDirectories.asFileTree.files
            .filter { it.name.endsWith(".class") }
            .map { f ->
                classDirectories.files.first { f.startsWith(it) }
                    .let { root -> f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            }
        val stale = patterns.keys.filter { p -> val re = globToRegex(p); classNames.none { re.matches(it) } }
        if (stale.isNotEmpty()) {
            throw GradleException("$projectPath: coverage exceptions match no class (stale, delete them): $stale")
        }
    }
}

// Verification must not be switched off: fix the code or add a narrow, reasoned exception.
afterEvaluate {
    if (!tasks.named("jacocoTestCoverageVerification").get().enabled) {
        throw GradleException("$path: jacocoTestCoverageVerification must not be disabled; add a narrow coverageConfig exception instead")
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
