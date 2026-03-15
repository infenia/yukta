/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
    id("com.infenia.yukta.library-conventions")
}

version = "1.0.0"

dependencies {
    implementation(project(":yukta-plugin-api"))
    implementation(project(":yukta-core"))
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
        "LINE" to 0.8,
        "BRANCH" to 0.2,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.8
    ))
    exceptions.put("com.infenia.yukta.plugin.core.util.MapMessageMapper", mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.6
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
        "LINE" to 0.8,
        "BRANCH" to 0.6,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.8
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
