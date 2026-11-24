// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
    `java-test-fixtures`
}

// The Plus edition (AGPL-3.0-only): edition info, the source offer and the Enterprise module API.
// Added to the application with -Pyukta.edition=plus. Nothing outside boot may depend on it.
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(testFixtures(project(":core")))

    // The contract tests an extension module runs against its stores and election (testFixtures)
    testFixturesApi(project(":core"))
    testFixturesApi(testFixtures(project(":core")))
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.reactor.test)
}

coverageConfig {
}
