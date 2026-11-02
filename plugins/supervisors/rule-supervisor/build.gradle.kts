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

// A test loads the example profile under examples/, so a broken example fails the build.
tasks.withType<Test>().configureEach {
    val example = rootProject.layout.projectDirectory.file("examples/supervisor-profiles/input-requests.json")
    inputs.file(example).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yukta.example.input-rules", example.asFile.absolutePath)
}
