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
    implementation(libs.caffeine)
    // Nashorn (GPL-2.0 with Classpath Exception) is only used by Handlebars' JavaScript helpers, which
    // the mapper never registers.
    implementation(libs.handlebars) {
        exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
    }
}
