// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}


dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.ai.client.chat)
    implementation(libs.spring.ai.openai)
    implementation(libs.spring.ai.anthropic)
    implementation(libs.spring.ai.ollama)
}

coverageConfig {
}
