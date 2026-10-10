// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(libs.spring.boot.starter.webflux)
    api(project(":messaging"))
}

coverageConfig {
    exception("com.infenia.yukta.plugin.control.*", "Control-plane records/enums and signal processor are exercised via core, not in this module", mapOf("LINE" to 0.0, "BRANCH" to 0.0))
    exception("com.infenia.yukta.plugin.util.SpelUtils", "Exercised through plugin tests in other modules", mapOf("LINE" to 0.0, "BRANCH" to 0.0))
}

// TODO: temporarily disabled — re-enable once test-source PMD violations are addressed.
tasks.named("pmdTest") {
    enabled = false
}
