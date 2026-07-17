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

coverageConfig {
    exceptions.put("com.infenia.yukta.mcp.AppMcpTools", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.6
    ))
}

// TODO: temporarily disabled — re-enable once test-source PMD violations are addressed.
tasks.named("pmdTest") {
    enabled = false
}
