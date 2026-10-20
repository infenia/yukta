// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// The Plus edition (AGPL-3.0-only): single-node durable storage on disk. Added to the application
// with -Pyukta.edition=plus. Nothing outside boot may depend on this module.
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)
}

coverageConfig {
}
