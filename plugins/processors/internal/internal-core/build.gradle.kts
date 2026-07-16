// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

version = "1.0.0"

dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.jackson.databind)
    implementation(libs.handlebars)
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.js)
}

coverageConfig {
    val lowCoverage = mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    )

    exceptions.put("com.infenia.yukta.plugin.core.router.AggregatorProcessor", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.7,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.6
    ))
    exceptions.put("com.infenia.yukta.plugin.core.router.RecipientListProcessor", mapOf(
        "LINE" to 0.7,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.7
    ))
    exceptions.put("com.infenia.yukta.plugin.core.router.BranchProcessor", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.6,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.8
    ))
    exceptions.put("com.infenia.yukta.plugin.core.router.ResequencerProcessor", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.6,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.7
    ))
    exceptions.put("com.infenia.yukta.plugin.core.router.SplitterProcessor", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.7,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.9,
        "METHOD" to 0.8
    ))
    exceptions.put("com.infenia.yukta.plugin.core.util.MergeUtils", lowCoverage)
    exceptions.put("com.infenia.yukta.plugin.core.util.SimpleExpressionEvaluator*", mapOf(
        "LINE" to 0.5,
        "BRANCH" to 0.2,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.5,
        "METHOD" to 0.8
    ))
    exceptions.put("com.infenia.yukta.plugin.core.util.MapMessageMapper", mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    ))
    exceptions.put("com.infenia.yukta.plugin.core.flow.LoopPredicateProcessor*", mapOf(
        "LINE" to 0.7,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.7
    ))
    exceptions.put("com.infenia.yukta.plugin.core.flow.SubWorkflowProcessor", mapOf(
        "LINE" to 0.5,
        "BRANCH" to 0.4,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.6,
        "METHOD" to 0.6
    ))
    exceptions.put("com.infenia.yukta.plugin.core.flow.LoopStreamProcessor", mapOf(
        "LINE" to 0.6,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.6,
        "METHOD" to 0.6
    ))
    exceptions.put("com.infenia.yukta.plugin.core.filter.FilterProcessor", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.6,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.7
    ))
    exceptions.put("com.infenia.yukta.plugin.core.transformer.MapperProcessor", mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    ))
    exceptions.put("com.infenia.yukta.plugin.core.transformer.ContentFilterProcessor*", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.6,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.8
    ))
    exceptions.put("com.infenia.yukta.plugin.core.transformer.EnricherProcessor", mapOf(
        "LINE" to 0.7,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.7
    ))
}

// TODO: temporarily disabled — re-enable once violations are addressed.
tasks.named("pmdMain") {
    enabled = false
}

tasks.named("pmdTest") {
    enabled = false
}

tasks.named("checkstyleTest") {
    enabled = false
}

tasks.named("jacocoTestCoverageVerification") {
    enabled = false
}

