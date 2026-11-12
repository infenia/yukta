// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import com.github.spotbugs.snom.SpotBugsTask
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
        googleJavaFormat().reflowLongStrings()
        trimTrailingWhitespace()
        endWithNewline()
        targetExclude("bin/**", "build/**", "out/**", "**/.gradle/**", "**/node_modules/**", "**/dist/**", "**/coverage/**")
    }

    kotlinGradle {
        target("*.gradle.kts")
        licenseHeaderFile(licenseHeader, "(plugins|id|import|apply)")
    }

    // A parent project (:plus) also walks its child projects' directories; each child formats its own
    // files too, which is harmless because the header is only added once.
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
    // Licences that a POM names only by URL (non-standard but compatible).
    // Flyway Community Edition is Apache-2.0, but its POM links the README instead of the licence; only
    // Flyway declares this URL. Needs a legal check before it ships in a distribution.
    allowUrl("https://github.com/flyway/flyway/blob/main/README.txt")
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
    constraints {
        // Resolve CVE-2025-67030: Plexus-utils directory traversal vulnerability
        add("checkstyle", "org.codehaus.plexus:plexus-utils:4.1.0")
        // Keep spotbugs-annotations on the version SpotBugs itself uses
        add("implementation", "com.github.spotbugs:spotbugs-annotations:${libs.findVersion("spotbugsAnnotations").get().requiredVersion}")
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

val opengrep = tasks.register<Exec>("opengrep") {
    description = "Run OpenGrep static analysis with the pinned local ruleset"
    group = "verification"
    usesService(opengrepExecution)

    // Production code only, on purpose: test source is covered by Checkstyle, PMD and SpotBugs, not by
    // the OpenGrep security rules. Rules scoped to tests in the ruleset therefore do not run here.
    val sourceDir = project.file("src/main")
    val ruleFile = rootProject.file("config/opengrep/.semgrep.yml")
    val reportDir = project.layout.buildDirectory.dir("reports/opengrep")
    val reportFile = reportDir.map { it.file("opengrep-report.sarif").asFile }
    val home = project.layout.buildDirectory.dir("tmp/opengrep-home")
    val rootDir = rootProject.projectDir
    val projectPath = path
    val onCi = providers.environmentVariable("CI").isPresent
    val required = providers.gradleProperty("yukta.requireOpengrep").isPresent
    // `opengrep --version`, absent when the program is not on the PATH. A provider (not shared state),
    // so it is safe in parallel and under the configuration cache; ponytail: one cheap probe per module.
    val scannerVersion = providers.provider {
        try {
            providers.exec { commandLine("opengrep", "--version") }.standardOutput.asText.get().trim()
        } catch (_: Exception) {
            null
        }
    }

    // Relative paths: the key must not depend on where the checkout is (worktrees, CI), or the
    // build cache never hits across them. The scanner version is an input, so an upgrade rescans.
    inputs.file(ruleFile).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(sourceDir).withPathSensitivity(PathSensitivity.RELATIVE).optional()
    inputs.property("opengrepVersion", scannerVersion.orElse("absent"))
    // Declared output makes the scan UP-TO-DATE / cacheable when sources and rules are unchanged.
    outputs.file(reportFile)
    outputs.cacheIf { true }

    // A per-project settings dir (not the shared tmpdir) so concurrent builds do not share it, and it
    // stays writable on a read-only home.
    doFirst {
        reportDir.get().asFile.mkdirs()
        home.get().asFile.mkdirs()
        environment("HOME", home.get().asFile)
    }

    // --error: any finding exits non-zero, which fails the build.
    // Run from the repo root with relative paths so rule ids stay short (`nosem: <id>` matches).
    workingDir = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "scan",
            "--config=config/opengrep/.semgrep.yml",
            "--error",
            "--sarif-output=${reportFile.get().absolutePath}",
            "--quiet",
            sourceDir.relativeTo(rootDir).path
        )
    })
    executable = "opengrep"

    onlyIf {
        val sourceExists = sourceDir.exists()
        val opengrepExists = scannerVersion.isPresent

        // A scanner that is missing must not read as "no findings" on CI, or where the developer asks
        // for it (-Pyukta.requireOpengrep, e.g. for a pre-push hook); elsewhere say so loudly.
        if (sourceExists && !opengrepExists) {
            if (onCi || required) {
                throw GradleException("opengrep is not on the PATH; CI must install it (see ci.yml) instead of skipping the scan")
            }
            logger.warn("WARNING: $projectPath:opengrep skipped, opengrep is not installed; CI will run it.")
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
// Registered per task type so no task is created just to be inspected (configuration avoidance).
tasks.withType<Checkstyle>().configureEach { if (name.contains("Aot")) enabled = false }
tasks.withType<Pmd>().configureEach { if (name.contains("Aot")) enabled = false }
tasks.withType<SpotBugsTask>().configureEach { if (name.contains("Aot")) enabled = false }

// Quality tasks that a module may switch off, each with the reason. Anything else fails the build,
// like lowering coverage does. Burn these down; do not add to them without a tracking issue.
val allowedDisabledQualityTasks = mapOf(
    ":mcp:pmdTest" to "336 test-source PMD violations still to fix (tracked in #2270)",
    ":ui" to "no Java sources; its JS checks run through node-conventions",
)

// Checked on the tasks that are about to run, once the graph is known: that sees every late change
// (a module's own afterEvaluate, ignoreFailures) and creates no task. `onlyIf { false }` cannot be read
// back from a task; code review has to catch that one.
fun isGate(t: Task) =
    !t.name.contains("Aot") &&
        (t is Checkstyle || t is Pmd || t is SpotBugsTask || t is JacocoCoverageVerification ||
            t.name.startsWith("spotless") || t.name in setOf("opengrep", "licensee", "verifyCoverageData"))

gradle.taskGraph.whenReady {
    allTasks.filter { it.project == project && isGate(it) }.forEach { t ->
        val allowed = "$path:${t.name}" in allowedDisabledQualityTasks || path in allowedDisabledQualityTasks
        val ignoring = (t as? VerificationTask)?.ignoreFailures == true
        if ((!t.enabled || ignoring) && !allowed) {
            throw GradleException("${t.path} must not be disabled or set to ignore failures; fix the findings or add a reasoned entry to allowedDisabledQualityTasks in quality-conventions")
        }
    }
}

tasks.named("check") {
    dependsOn(opengrep)
}
