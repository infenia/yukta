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

dependencies {
    api(project(":yukta-plugin-api"))

    // Default plugins
    implementation(project(":plugins:build-tools:gradle"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.mapstruct)

    implementation(libs.springdoc.openapi)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok.mapstruct.binding)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.webclient.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.jackson.test)
}

coverageConfig {
    exceptions.put("com.infenia.yukta.service.TaskTrackerService", mapOf("LINE" to 0.99, "BRANCH" to 0.94, "INSTRUCTION" to 0.98, "METHOD" to 0.97))
    exceptions.put("com.infenia.yukta.service.TaskTrackerService.*WorkflowState", mapOf("BRANCH" to 0.91))
    exceptions.put("com.infenia.yukta.service.WorkflowOrchestrator", mapOf("LINE" to 0.95, "BRANCH" to 0.88, "INSTRUCTION" to 0.96, "METHOD" to 0.92))
    exceptions.put("com.infenia.yukta.service.WorkflowService", mapOf("LINE" to 0.90, "INSTRUCTION" to 0.93, "METHOD" to 0.87))
}
