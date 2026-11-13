// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(libs.reactor.core)
    api("jakarta.annotation:jakarta.annotation-api")
    implementation(libs.spring.expression)
    implementation(libs.jackson.databind)
    api(project(":messaging"))

    // validates the plugin wire schemas (schema/plugin/v1)
    testImplementation(libs.mcp.json.jackson3)

    testFixturesImplementation(project(":messaging"))
    testFixturesImplementation(libs.reactor.core)
    // PluginConformance (test fixtures) watches the logs for leaked secrets
    testFixturesImplementation("ch.qos.logback:logback-classic")
}

coverageConfig {
}
