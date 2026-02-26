plugins {
    id("com.diffplug.spotless")
    checkstyle
    pmd
    id("com.github.spotbugs")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
    java {
        licenseHeaderFile(rootProject.file("config/license/header.txt"))
        importOrder()
        removeUnusedImports()
        cleanthat()
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
}

pmd {
    toolVersion = libs.findVersion("pmd").get().requiredVersion
    ruleSets = listOf(rootProject.file("config/pmd/ruleset.xml").absolutePath)
    isIgnoreFailures = false
    isConsoleOutput = true
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Disable quality tasks for everything except main
tasks.configureEach {
    val task = this
    if ((task.name.contains("Aot") || task.name.contains("Test")) &&
        (task is Checkstyle || task is Pmd || task::class.java.name.contains("SpotBugs"))) {
        task.enabled = false
    }
}
