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
}

tasks.register<Exec>("semgrep") {
    description = "Run Semgrep static analysis"
    group = "verification"

    val sourceDir = project.file("src/main/java")
    val configFile = rootProject.file("config/semgrep/.semgrep.yml")
    val reportDir = project.layout.buildDirectory.dir("reports/semgrep").get()
    val reportFile = reportDir.file("semgrep-report.sarif").asFile

    doFirst {
        reportDir.asFile.mkdirs()
    }

    // Use temp directory for semgrep settings to avoid read-only filesystem issues
    val tempDir = System.getProperty("java.io.tmpdir")
    environment("HOME", tempDir)

    commandLine("semgrep", "scan",
        "--config=${configFile.absolutePath}",
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
            throw GradleException("Semgrep scan failed with exit code ${executionResult.get().exitValue}")
        }
    }

    onlyIf {
        val sourceExists = sourceDir.exists()
        val semgrepExists = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "which semgrep")).waitFor() == 0
        } catch (e: Exception) {
            false
        }

        sourceExists && semgrepExists
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
        task.name == "semgrep" ||
        task.name.startsWith("spotless")

    if (isQualityTask && task.name.contains("Aot")) {
        task.enabled = false
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<Task>().matching { it.name == "semgrep" })
}
