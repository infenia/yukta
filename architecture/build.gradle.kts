// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.infenia.yukta.library-conventions")
    `java-test-fixtures`
}

// Test-only module: it holds the ArchUnit rules and needs every Java module on its classpath.
dependencies {
    testImplementation(project(":messaging"))
    testImplementation(project(":plugin-api"))
    testImplementation(project(":core"))
    testImplementation(project(":web"))
    testImplementation(project(":mcp"))
    testImplementation(project(":plugins:processors:process-executor"))
    testImplementation(project(":plugins:external"))
    testImplementation(project(":plugins:processors:http-request"))
    testImplementation(project(":plugins:processors:mcp-tool-call"))
    testImplementation(project(":plugins:processors:file-io"))
    testImplementation(project(":plugins:processors:sql-query"))
    testImplementation(project(":plugins:processors:llm-call"))
    testImplementation(project(":plugins:processors:decision"))
    testImplementation(project(":plugins:processors:internal:internal-core"))
    testImplementation(project(":plugins:processors:internal:script-js"))
    testImplementation(project(":plugins:triggers:api-trigger"))
    testImplementation(project(":plugins:triggers:constant-source"))
    testImplementation(project(":plugins:triggers:schedule-trigger"))
    testImplementation(project(":plugins:triggers:webhook-trigger"))
    testImplementation(project(":plugins:terminals:console-terminal"))
    testImplementation(project(":plugins:terminals:notify-terminal"))
    testImplementation(project(":plugins:terminals:outcome-terminal"))
    testImplementation(project(":plugins:supervisors:rules"))
    testImplementation(project(":plugins:supervisors:process-engine"))
    // The shared rules and helpers (testFixtures) are reused by plus:plus-architecture
    testFixturesApi(libs.archunit.junit5)
    testImplementation(libs.reactor.core)
    testImplementation(libs.spring.boot.starter)
}

// A failing rule lists every violating dependency, also in the CI log
tasks.withType<Test>().configureEach {
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}
