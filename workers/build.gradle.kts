// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// Remote workers over RSocket: the control plane side (a server that workers register with) and
// the worker side (an agent that connects out). Added to the application with
// -Pyukta.workers=true; see core/docs/plans/10-remote-workers.md.
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))
    implementation(libs.spring.boot.starter.rsocket)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.rsocket.test)
    testImplementation(libs.awaitility)
}

coverageConfig {
}
