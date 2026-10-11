// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

dependencies {
    api(project(":plugin-api"))
    implementation(libs.spring.expression)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.jackson.databind)
}
