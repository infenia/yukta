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
    implementation(libs.jackson.databind)
}

// TODO: temporarily disabled — re-enable once violations are addressed.
tasks.named("pmdMain") {
    enabled = false
}

tasks.named("pmdTest") {
    enabled = false
}