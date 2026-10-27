// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    id("com.diffplug.spotless")
    checkstyle
    pmd
    id("com.github.spotbugs")
    id("net.ltgt.errorprone")
    id("app.cash.licensee")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The plus module is AGPL-3.0-only; everything else is Apache-2.0 (see LICENSING.md)
val licenseHeader = rootProject.file(if (path == ":plus" || path.startsWith(":plus:")) "config/license/header-agpl.txt" else "config/license/header.txt")

spotless {
    java {
        licenseHeaderFile(licenseHeader)
        importOrder()
//        removeUnusedImports()
//        cleanthat()
        googleJavaFormat().reflowLongStrings()
        leadingTabsToSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
        targetExclude("bin/**", "build/**", "out/**", "**/.gradle/**", "**/node_modules/**", "**/dist/**", "**/coverage/**", "**/.gradle/**")
    }

    kotlinGradle {
        target("*.gradle.kts", "build-logic/**/*.gradle.kts")
        licenseHeaderFile(licenseHeader, "(plugins|id|import|apply)")
    }

    format("xml") {
        target("**/*.xml")
        targetExclude("**/build/**", "**/bin/**", "**/out/**", "**/.gradle/**", "**/node_modules/**", "**/test-results/**", "**/playwright-report/**")
        licenseHeaderFile(rootProject.file("config/license/header-xml.txt"), "(<[^!?])")
    }
}

licensee {
    // Allow Apache-2.0 compatible licenses
    allow("Apache-2.0")
    allow("MIT")
    allow("MIT-0")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("ISC")
    allow("Unlicense")
    allow("0BSD")
    // Common open-source licenses compatible with Apache-2.0
    allow("Python-2.0")
    // Java/Spring ecosystem licenses
    allow("EPL-1.0")
    allow("EPL-2.0")
    allow("UPL-1.0")
    // Other permissive licenses
    // Per-artifact decisions for weak/strong-copyleft licences (statically linked into the native
    // image; needs legal sign-off before distribution). Nothing else may use these licences.
    // Flyway Community Edition is Apache-2.0 (its POM only links the README); needs a legal check
    // before it ships in a distribution.
    allowDependency("org.flywaydb", "flyway-core", "12.4.0") {
        because("Apache-2.0 (Flyway Community Edition; POM links the README); used by persistence-postgres")
    }
    allowDependency("org.flywaydb", "flyway-database-postgresql", "12.4.0") {
        because("Apache-2.0 (Flyway Community Edition; POM links the README); used by persistence-postgres")
    }
    // Allow specific license URLs (non-standard but compatible)
    allowUrl("https://spdx.org/licenses/BSD-2-Clause")
    allowUrl("https://jdbc.postgresql.org/about/license.html")
    allowUrl("http://antlr.org/license.html")
    allowUrl("https://www.antlr.org/license.html")
    allowUrl("http://www.antlr.org/license.html")
    allowUrl("https://asm.ow2.io/license.html")
    allowUrl("https://github.com/webjars/webjars-locator-lite/blob/main/LICENSE.md")
    allowUrl("https://raw.githubusercontent.com/unicode-org/icu/main/LICENSE")
    allowUrl("https://repository.jboss.org/licenses/apache-2.0.txt")
    allowUrl("http://www.eclipse.org/org/documents/edl-v10.php")
    allowUrl("https://www.eclipse.org/org/documents/edl-v10.php")
    allowUrl("https://opensource.org/license/mit")
    allowUrl("http://opensource.org/licenses/UPL")
    allowUrl("https://opensource.org/licenses/UPL")
}


checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    isShowViolations = true
    maxWarnings = 0
}

pmd {
    toolVersion = libs.findVersion("pmd").get().requiredVersion
    ruleSets = listOf(rootProject.file("config/pmd/ruleset.xml").absolutePath)
    isIgnoreFailures = false
    isConsoleOutput = true
}

spotbugs {
    excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}

// Error Prone configuration
dependencies {
    add("errorprone", libs.findLibrary("errorprone").get())
    // Resolve CVE-2025-67030: Plexus-utils directory traversal vulnerability
    constraints {
        add("checkstyle", "org.codehaus.plexus:plexus-utils:3.6.1")
        // Keep spotbugs-annotations on the version SpotBugs itself uses
        add("implementation", "com.github.spotbugs:spotbugs-annotations:4.10.3")
    }
}

// Skip generated sources (MapStruct mappers, JTE templates) we don't hand-edit and that get
// regenerated on every build.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        excludedPaths.set(".*/build/generated/.*")
    }
}

// Serializes OpenGrep runs across modules: parallel `execvp` of the opengrep wrapper can fail with
// "Text file busy" (ETXTBSY) while the binary is being prepared, which flakes the build.
abstract class OpengrepExecutionService : BuildService<BuildServiceParameters.None>

val opengrepExecution = gradle.sharedServices.registerIfAbsent(
    "opengrepExecution",
    OpengrepExecutionService::class.java
) {
    maxParallelUsages.set(1)
}

tasks.register<Exec>("opengrep") {
    description = "Run OpenGrep static analysis with the pinned local ruleset"
    group = "verification"
    usesService(opengrepExecution)

    val sourceDir = project.file("src/main")
    // Tests too: the yukta-test-thread-sleep rule only has something to flag there.
    val scanDirs = listOf("src/main", "src/test", "src/testFixtures").map { project.file(it) }.filter { it.exists() }
    val ruleFile = rootProject.file("config/opengrep/.semgrep.yml")
    val reportDir = project.layout.buildDirectory.dir("reports/opengrep").get()
    val reportFile = reportDir.file("opengrep-report.sarif").asFile

    inputs.file(ruleFile)
    scanDirs.forEach { inputs.dir(it) }
    // Declared output makes the scan UP-TO-DATE / cacheable when sources and rules are unchanged.
    outputs.file(reportFile)
    outputs.cacheIf { true }

    doFirst {
        reportDir.asFile.mkdirs()
    }

    // Use temp directory for opengrep settings to avoid read-only filesystem issues
    val tempDir = System.getProperty("java.io.tmpdir")
    environment("HOME", tempDir)

    // --error: any finding exits non-zero, which fails the build.
    // Run from the repo root with relative paths so rule ids stay short (`nosem: <id>` matches).
    workingDir = rootProject.projectDir
    commandLine("opengrep", "scan",
        "--config=config/opengrep/.semgrep.yml",
        "--error",
        "--sarif-output=${reportFile.absolutePath}",
        "--quiet",
        *scanDirs.map { it.relativeTo(rootProject.projectDir).path }.toTypedArray()
    )

    onlyIf {
        val sourceExists = sourceDir.exists()
        // Probe once per build, not once per module.
        val opengrepExists = rootProject.extra.let { extra ->
            if (!extra.has("opengrepInstalled")) {
                extra["opengrepInstalled"] = try {
                    ProcessBuilder("opengrep", "--version").redirectErrorStream(true).start().let {
                        it.inputStream.readAllBytes()
                        it.waitFor() == 0
                    }
                } catch (_: java.io.IOException) {
                    false
                }
            }
            extra["opengrepInstalled"] as Boolean
        }

        // A scanner that is missing must not read as "no findings" on CI, or where the developer asks
        // for it (-Pyukta.requireOpengrep, e.g. for a pre-push hook); elsewhere say so loudly.
        if (sourceExists && !opengrepExists) {
            if (System.getenv("CI") != null || providers.gradleProperty("yukta.requireOpengrep").isPresent) {
                throw GradleException("opengrep is not on the PATH; CI must install it (see ci.yml) instead of skipping the scan")
            }
            logger.warn("WARNING: $path:opengrep skipped, opengrep is not installed; CI will run it.")
        }

        sourceExists && opengrepExists
    }
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Disable quality tasks for AOT generated code
tasks.configureEach {
    val task = this
    val isQualityTask = task is Checkstyle || task is Pmd ||
        task::class.java.name.contains("SpotBugs") ||
        task.name == "opengrep" ||
        task.name.startsWith("spotless")

    if (isQualityTask && task.name.contains("Aot")) {
        task.enabled = false
    }
}

// Quality tasks that a module may switch off, each with the reason. Anything else fails the build,
// like lowering coverage does. Burn these down; do not add to them without a tracking issue.
val allowedDisabledQualityTasks = mapOf(
    ":mcp:pmdTest" to "336 test-source PMD violations still to fix",
    ":ui" to "no Java sources; its JS checks run through node-conventions",
)

afterEvaluate {
    tasks.matching { t ->
        !t.enabled && !t.name.contains("Aot") &&
            (t is Checkstyle || t is Pmd || t::class.java.name.contains("SpotBugs") || t.name.startsWith("spotless"))
    }.forEach { t ->
        if ("$path:${t.name}" !in allowedDisabledQualityTasks && path !in allowedDisabledQualityTasks) {
            throw GradleException("${t.path} must not be disabled; fix the findings or add a reasoned entry to allowedDisabledQualityTasks in quality-conventions")
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<Task>().matching { it.name == "opengrep" })
}
