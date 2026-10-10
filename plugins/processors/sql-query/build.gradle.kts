// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}


dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(libs.spring.boot.starter.webflux)
    // JDBC drivers are supplied by the deployer; H2 is only the test database.
    testRuntimeOnly(libs.h2)
}

coverageConfig {
}
