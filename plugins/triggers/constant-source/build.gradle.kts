// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

version = "1.0.0"

dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.jackson.databind)
}

coverageConfig {
    val baselineCoverage = mapOf(
        "LINE" to 0.5,
        "BRANCH" to 0.5,
        "CLASS" to 0.5,
        "INSTRUCTION" to 0.5,
        "METHOD" to 0.5
    )

    exceptions.put("com.infenia.yukta.plugin.trigger.ConstantSource", baselineCoverage)
}
