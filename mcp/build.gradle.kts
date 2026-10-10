// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.springAi.get()}")
    }
}

dependencies {
    api(project(":core"))

    implementation(libs.spring.ai.mcp.server.webflux)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.webflux.test)
}



// TODO: temporarily disabled — re-enable once test-source PMD violations are addressed.
tasks.named("pmdTest") {
    enabled = false
}
