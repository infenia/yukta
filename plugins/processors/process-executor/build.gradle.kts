// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}


dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(libs.spring.boot.starter.webflux)
}

coverageConfig {
}

// The environment tests expect a server variable besides PATH. Set it here so they do not depend on
// the runner (a clean environment, such as the `yk` runner's, has no HOME).
tasks.withType<Test>().configureEach {
    environment("HOME", System.getProperty("user.home"))
}
