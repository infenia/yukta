// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

dependencies {
    api(project(":plugin-api"))
    implementation(project(":plugins:control-planes:rule-control-plane"))
    implementation(project(":plugins:control-planes:systemone-control-plane"))
    implementation(project(":plugins:supervisors:rule-supervisor"))
    implementation(libs.spring.boot.starter.webflux)
}
