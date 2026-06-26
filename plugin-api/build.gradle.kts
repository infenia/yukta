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
