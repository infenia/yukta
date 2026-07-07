import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("com.diffplug.spotless")
    checkstyle
    pmd
    id("com.github.spotbugs")
    id("net.ltgt.errorprone")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
    java {
        licenseHeaderFile(rootProject.file("config/license/header.txt"))
        importOrder()
//        removeUnusedImports()
//        cleanthat()
        googleJavaFormat().reflowLongStrings()
        leadingTabsToSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
        targetExclude("bin/**", "build/**", "out/**", "**/.gradle/**")
    }

    kotlinGradle {
        target("*.gradle.kts", "build-logic/**/*.gradle.kts")
        licenseHeaderFile(rootProject.file("config/license/header.txt"), "(plugins|id|import|apply)")
    }

    format("xml") {
        target("**/*.xml")
        targetExclude("**/build/**", "**/bin/**", "**/out/**", "**/.gradle/**", "**/node_modules/**")
        licenseHeaderFile(rootProject.file("config/license/header-xml.txt"), "(<[^!?])")
    }
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
    }
}

// Skip generated sources (MapStruct mappers, JTE templates) we don't hand-edit and that get
// regenerated on every build.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        excludedPaths.set(".*/build/generated/.*|.*/build/jte-classes/.*")
    }
}

tasks.register<Exec>("opengrep") {
    description = "Run OpenGrep static analysis"
    group = "verification"

    val sourceDir = project.file("src/main/java")
    val reportDir = project.layout.buildDirectory.dir("reports/opengrep").get()
    val reportFile = reportDir.file("opengrep-report.sarif").asFile

    doFirst {
        reportDir.asFile.mkdirs()
    }

    // Use temp directory for opengrep settings to avoid read-only filesystem issues
    val tempDir = System.getProperty("java.io.tmpdir")
    environment("HOME", tempDir)

    commandLine("opengrep", "scan",
        "--config=auto",
        "--output=${reportFile.absolutePath}",
        "--json",
        "--quiet",
        sourceDir.absolutePath
    )

    isIgnoreExitValue = true

    doLast {
        // Exit code 1 means findings were reported, which is not a failure
        // Exit code 0 means no findings
        if (executionResult.get().exitValue > 1) {
            throw GradleException("OpenGrep scan failed with exit code ${executionResult.get().exitValue}")
        }
    }

    onlyIf {
        val sourceExists = sourceDir.exists()
        val opengrepExists = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "which opengrep")).waitFor() == 0
        } catch (_: Exception) {
            false
        }

        sourceExists && opengrepExists
    }
}

tasks.register<Exec>("trivy") {
    description = "Scan for vulnerabilities with Trivy"
    group = "verification"

    val reportDir = project.layout.buildDirectory.dir("reports/trivy").get().asFile
    val fsSarifReport = File(reportDir, "trivy-fs-report.sarif")

    doFirst {
        reportDir.mkdirs()
    }

    commandLine("sh", "-c", """
        trivy fs \
          --format sarif \
          --output ${fsSarifReport.absolutePath} \
          --severity HIGH,CRITICAL \
          --exit-code 0 \
          ${project.projectDir.absolutePath}
    """.trimIndent())

    isIgnoreExitValue = true

    doLast {
        val reportExists = fsSarifReport.exists()
        if (reportExists) {
            logger.info("✓ Trivy filesystem scan report: ${fsSarifReport.absolutePath}")
        }

        // Exit code 1 means vulnerabilities found (not a failure for this task in Gradle)
        // We report findings but don't fail - that's the CI job's responsibility
        if (executionResult.get().exitValue > 1) {
            throw GradleException("Trivy scan failed with exit code ${executionResult.get().exitValue}")
        }
    }

    onlyIf {
        val trivyExists = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "which trivy")).waitFor() == 0
        } catch (_: Exception) {
            false
        }

        if (!trivyExists) {
            logger.warn("⚠ Trivy not found. Install with: curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin")
        }

        trivyExists
    }
}

// Make Trivy part of the check task (runs after check completes)
tasks.named("check") {
    finalizedBy(tasks.named("trivy"))
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

tasks.named("check") {
    dependsOn(tasks.withType<Task>().matching { it.name == "opengrep" })
}
