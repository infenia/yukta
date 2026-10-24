// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// Test support for every module's tests (see ErrorGuardExtension). Each backend is optional, so a
// module that lacks Reactor or Logback still works: the matching probe is simply not used.
dependencies {
    compileOnly("org.junit.jupiter:junit-jupiter-api")
    compileOnly("org.junit.platform:junit-platform-commons")
    compileOnly(libs.reactor.core)
    compileOnly("ch.qos.logback:logback-classic")
}

coverageConfig {
    exception(
        "com.infenia.yukta.testsupport.ErrorGuardExtension",
        "The branches for a missing Reactor or Logback class cannot run while both are on the test classpath",
        mapOf("BRANCH" to 0.8)
    )
}
