// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

dependencies {
    api(libs.reactor.core)
    api(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter) // slf4j for the debug log of dropped null headers
}

coverageConfig {
}
