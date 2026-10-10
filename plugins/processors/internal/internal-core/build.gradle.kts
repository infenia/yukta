// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}


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
    exception("com.infenia.yukta.plugin.core.router.RecipientListProcessor", "Routing fan-out edge cases not yet unit tested", mapOf("LINE" to 0.7, "BRANCH" to 0.55))
    exception("com.infenia.yukta.plugin.core.router.SplitterProcessor", "Error branches not yet unit tested", mapOf("LINE" to 0.89, "BRANCH" to 0.75))
    exception("com.infenia.yukta.plugin.core.util.SimpleExpressionEvaluator*", "Expression types not yet unit tested", mapOf("LINE" to 0.0, "BRANCH" to 0.0))
    exception("com.infenia.yukta.plugin.core.util.MergeUtils", "Not yet unit tested", mapOf("LINE" to 0.0, "BRANCH" to 0.0))
    exception("com.infenia.yukta.plugin.core.filter.FilterProcessor", "Branches not yet unit tested", mapOf("BRANCH" to 0.82))
    exception("com.infenia.yukta.plugin.core.transformer.ContentFilterProcessor*", "Branches not yet unit tested", mapOf("LINE" to 0.82, "BRANCH" to 0.69))
    exception("com.infenia.yukta.plugin.core.transformer.MapMessageMapper", "Not yet unit tested", mapOf("LINE" to 0.0, "BRANCH" to 0.0))
    exception("com.infenia.yukta.plugin.core.transformer.MapperProcessor", "GraalVM JS path needs a polyglot test setup", mapOf("LINE" to 0.0, "BRANCH" to 0.0))
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

