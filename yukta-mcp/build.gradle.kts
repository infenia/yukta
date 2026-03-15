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

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.springAi.get()}")
    }
}

dependencies {
    api(project(":yukta-core"))

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
