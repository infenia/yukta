// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.infenia.yukta.library-conventions")
}

// Test-only module (like :architecture): scenarios that drive a real Yukta server over HTTP and MCP.
// The server is the app started in the test JVM, or any URL: -Pyukta.e2e.url=<url> -Pyukta.e2e.key=<ADMIN key>.
dependencies {
    testImplementation(project(":boot"))
    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(project(":test-support"))
    testImplementation(libs.awaitility)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    // the MCP SDK client (HttpClientStreamableHttpTransport); the version comes with the server starter
    testImplementation(libs.spring.ai.mcp.server.webflux)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("yukta.e2e.url").orNull?.let { systemProperty("yukta.e2e.url", it) }
    providers.gradleProperty("yukta.e2e.key").orNull?.let { systemProperty("yukta.e2e.key", it) }
    // a failing scenario shows its assertion in the CI log
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

coverageConfig {
    noTests("test-only module: it has no main classes")
}

// Integration coverage: which code the e2e tiers reach, apart from the unit floors. The server runs with
// the JaCoCo agent (release/e2e-server.sh, JAVA_TOOL_OPTIONS=-javaagent:<jacocoAgentJar>=destfile=<exec>),
// the Java, CLI and UI tiers run against it, and stopping it writes the exec file that
// e2eCoverageReport turns into a report over web, core, mcp and the plugins. It never gates anything.
val jacocoAgentJar =
    tasks.register<Copy>("jacocoAgentJar") {
        description = "Copies jacocoagent.jar (the version the build's JaCoCo uses) to build/jacoco-agent."
        from({ zipTree(configurations.getByName("jacocoAgent").singleFile) }) { include("jacocoagent.jar") }
        into(layout.buildDirectory.dir("jacoco-agent"))
    }

val covered =
    listOf(":web", ":core", ":mcp") +
        rootProject.subprojects.map { it.path }.filter { it.startsWith(":plugins:") }

covered.forEach { evaluationDependsOn(it) }

val mainSources =
    covered.mapNotNull { path ->
        project(path).extensions.findByType<SourceSetContainer>()?.findByName("main")
    }

tasks.register<JacocoReport>("e2eCoverageReport") {
    group = "verification"
    description = "Report over the classes of web, core, mcp and the plugins from the e2e server's exec file."
    // The conventions make every JacocoReport depend on the tests; this one reads a file the server wrote.
    setDependsOn(emptyList<Any>())
    executionData(
        providers.gradleProperty("yukta.e2e.exec").orElse("build/e2e-server/jacoco.exec").map { rootProject.file(it) })
    classDirectories.setFrom(mainSources.map { it.output.classesDirs })
    sourceDirectories.setFrom(mainSources.flatMap { it.allJava.srcDirs })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/e2e/jacocoE2e.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/e2e/html"))
    }
}
