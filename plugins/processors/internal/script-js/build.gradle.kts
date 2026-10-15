// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

version = "1.0.0"

dependencies {
    api(project(":messaging"))
    implementation(project(":plugins:processors:internal:internal-core"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.caffeine)
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.js)
}

coverageConfig {
}
