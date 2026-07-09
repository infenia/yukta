// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(libs.spring.boot.starter.webflux)
    api(project(":messaging"))
}

coverageConfig {

    val lowCoverage = mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    )

    exceptions.put("com.infenia.yukta.plugin.control.*", lowCoverage)
    exceptions.put("com.infenia.yukta.plugin.exception.*", mapOf(
        "LINE" to 0.5,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.4,
        "METHOD" to 0.5
    ))
    exceptions.put("com.infenia.yukta.plugin.gateway.AbstractMessagingGateway", mapOf(
        "LINE" to 0.4,
        "BRANCH" to 0.1,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.5,
        "METHOD" to 0.6
    ))
    exceptions.put("com.infenia.yukta.plugin.core.WorkflowPlugin", mapOf(
        "LINE" to 0.6,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.6,
        "METHOD" to 0.6
    ))
    exceptions.put("com.infenia.yukta.plugin.core.Plugin", lowCoverage)
}
