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

    // PluginConformance (test fixtures) watches the logs for leaked secrets
    testFixturesImplementation(project(":messaging"))
    testFixturesImplementation(libs.reactor.core)
    testFixturesImplementation("ch.qos.logback:logback-classic")
}

coverageConfig {
}
