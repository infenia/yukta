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
    /** Class-name pattern -> {LINE|BRANCH -> minimum ratio}. Other counters are ignored. Set through exception(). */
    internal abstract val exceptions: MapProperty<String, Map<String, Double>>

    /** Class-name pattern -> why it is allowed below the floor. Required when a ratio is < 0.80. */
    internal abstract val reasons: MapProperty<String, String>

    /** Why a module that has classes legitimately has no tests; empty means it must have them. */
    internal abstract val noTestsReason: Property<String>

    /** For a module that has classes but legitimately no tests, for example an API-only module. */
    fun noTests(reason: String) {
        require(reason.isNotBlank()) { "coverageConfig.noTests needs a reason" }
        noTestsReason.set(reason)
    }

    fun exception(pattern: String, reason: String, thresholds: Map<String, Double>) {
        val unknown = thresholds.keys - setOf("LINE", "BRANCH")
        require(unknown.isEmpty()) { "coverage exception '$pattern': unknown counters $unknown (use LINE or BRANCH)" }
        exceptions.put(pattern, thresholds)
        reasons.put(pattern, reason)
    }
}

val extension = extensions.create<CoverageExtension>("coverageConfig")
extension.exceptions.convention(emptyMap())
extension.reasons.convention(emptyMap())
extension.noTestsReason.convention("")

/*
 * Configure test tasks
 */
tasks.withType<Test>().configureEach {
    // Finalize with the reports only on request (-Pcoverage); `check` still builds the XML and HTML
    // because jacocoTestCoverageVerification depends on them (CI uploads the XML to Codecov).
    if (providers.gradleProperty("coverage").isPresent) {
        finalizedBy(tasks.withType<JacocoReport>())
    }

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
val defaults = mapOf("LINE" to 1.00.toBigDecimal(), "BRANCH" to 1.00.toBigDecimal())

// Configured after the module's own coverageConfig block has run, so the exceptions are never read empty.
afterEvaluate {
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
            val globToRegex = { glob: String ->
                Regex(glob.split("*").joinToString(".*") { part -> part.split("?").joinToString(".") { Regex.escape(it) } })
            }
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
}

// Verification must not be switched off: quality-conventions fails the build when the task is disabled.

// Gradle skips the verification above when there is no execution data, so a module whose tests were
// deleted, filtered out or never run would pass `check` with no coverage at all. This task fails then.
abstract class VerifyCoverageData : DefaultTask() {
    @get:Input abstract val projectPath: Property<String>
    @get:Input abstract val noTestsReason: Property<String>
    @get:Internal abstract val execFiles: ConfigurableFileCollection
    @get:Internal abstract val classDirs: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val hasClasses = classDirs.asFileTree.files.any { it.name.endsWith(".class") }
        if (hasClasses && noTestsReason.get().isEmpty() && execFiles.files.none { it.exists() }) {
            throw GradleException(
                "${projectPath.get()}: no coverage data (tests deleted, filtered out or not run); " +
                    "call coverageConfig.noTests(\"reason\") if this module legitimately has no tests"
            )
        }
    }
}

val verifyCoverageData = tasks.register<VerifyCoverageData>("verifyCoverageData") {
    projectPath.set(project.path)
    noTestsReason.set(extension.noTestsReason)
    mustRunAfter(tasks.withType<Test>())
    // Where the JaCoCo agent writes by default: build/jacoco/<test task>.exec
    execFiles.from(layout.buildDirectory.asFileTree.matching { include("jacoco/*.exec") })
}
plugins.withType<JavaPlugin> {
    val mainClasses = project.extensions.getByType<SourceSetContainer>()["main"].output.classesDirs
    verifyCoverageData.configure { classDirs.from(mainClasses) }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>(), verifyCoverageData)
}
