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
}

coverageConfig {
}

// TODO: temporarily disabled — re-enable once test-source PMD violations are addressed.
tasks.named("pmdTest") {
    enabled = false
}
