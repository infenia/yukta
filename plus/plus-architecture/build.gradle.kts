// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.infenia.yukta.library-conventions")
}

// Test-only module for the Plus edition's architecture rules. Only plus/** and boot may depend on
// :plus (LICENSING.md), which is why these rules do not live in the Apache :architecture module.
// The plugin modules are checked against Plus by the license-compliance CI job.
dependencies {
    testImplementation(project(":messaging"))
    testImplementation(project(":plugin-api"))
    testImplementation(project(":core"))
    testImplementation(project(":web"))
    testImplementation(project(":mcp"))
    testImplementation(project(":plus"))
    testImplementation(project(":plus:workers"))
    testImplementation(project(":plus:persistence-postgres"))
    testImplementation(testFixtures(project(":architecture")))
    testImplementation(libs.reactor.core)
    testImplementation(libs.spring.boot.starter)
}

// A failing rule lists every violating dependency, also in the CI log
tasks.withType<Test>().configureEach {
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}
