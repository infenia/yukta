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
    testImplementation("org.junit.jupiter:junit-jupiter-params") // the Spring Boot BOM names the version
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
    // BoundaryFuzzE2eTest: -PfuzzTries=<cases per entry point>, -PfuzzSeed=<number>|random (nightly)
    providers.gradleProperty("fuzzTries").orNull?.let { systemProperty("yukta.fuzz.tries", it) }
    providers.gradleProperty("fuzzSeed").orNull?.let { systemProperty("yukta.fuzz.seed", it) }
    // a failing scenario shows its assertion in the CI log
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

coverageConfig {
    noTests("test-only module: it has no main classes")
}
