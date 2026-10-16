// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(project(":plugins:processors:llm-call"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.jackson.databind)
}

coverageConfig {
}
