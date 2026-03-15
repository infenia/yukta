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
    `java-library`
    id("com.infenia.yukta.java-conventions")
    id("com.infenia.yukta.quality-conventions")
    id("com.infenia.yukta.jacoco-conventions")
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
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

    implementation(libs.spring.ai.mcp.server)
    implementation(libs.springdoc.openapi)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok.mapstruct.binding)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.webclient.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.jackson.test)
    testImplementation(libs.reactor.test)
}

coverageConfig {
    val baselineCoverage = mapOf(
        "LINE" to 0.8,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.8,
        "METHOD" to 0.8
    )

    val lowCoverage = mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    )

    exceptions.put("com.infenia.yukta.mapper.*Impl", baselineCoverage)
    exceptions.put("com.infenia.yukta.model.*", lowCoverage)
    exceptions.put("com.infenia.yukta.service.aggregate.InMemoryAggregateStore*", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.resequence.InMemoryResequencerStore*", mapOf(
        "LINE" to 0.7,
        "BRANCH" to 0.5,
        "CLASS" to 0.8,
        "INSTRUCTION" to 0.7,
        "METHOD" to 0.8
    ))
    exceptions.put("com.infenia.yukta.service.resequence.ResequencerStore*", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.TaskTrackerService", mapOf("LINE" to 0.99, "BRANCH" to 0.94, "INSTRUCTION" to 0.98, "METHOD" to 0.97))
    exceptions.put("com.infenia.yukta.service.TaskTrackerService.*WorkflowState", mapOf("BRANCH" to 0.91))
    exceptions.put("com.infenia.yukta.service.WorkflowService", mapOf("LINE" to 0.91, "INSTRUCTION" to 0.93, "METHOD" to 0.87))
    exceptions.put("com.infenia.yukta.service.NoOpSecretProvider", lowCoverage)
    exceptions.put("com.infenia.yukta.service.SessionService", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.LogRetrievalService", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.WorkflowRegistry", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.WorkflowValidator", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.ControlBusService", baselineCoverage)
    exceptions.put("com.infenia.yukta.service.DefaultControlBusGateway", lowCoverage)
    exceptions.put("com.infenia.yukta.service.WorkflowOrchestrator", mapOf("LINE" to 0.95, "BRANCH" to 0.88, "INSTRUCTION" to 0.96, "METHOD" to 0.92))
    exceptions.put("com.infenia.yukta.service.join.InMemoryJoinStore", mapOf("LINE" to 0.9, "INSTRUCTION" to 0.9))
    exceptions.put("com.infenia.yukta.service.join.InMemoryJoinStore*", lowCoverage)
    exceptions.put("com.infenia.yukta.service.join.JoinStore*", baselineCoverage)
    exceptions.put("com.infenia.yukta.config.AppConfigService", baselineCoverage)
    exceptions.put("com.infenia.yukta.config.AppConfiguration", lowCoverage)
    exceptions.put("gg.jte.generated.**", baselineCoverage)

    // Current coverage gaps to be addressed later
    exceptions.put("com.infenia.yukta.exception.GlobalExceptionHandler", mapOf("BRANCH" to 0.6))
    exceptions.put("com.infenia.yukta.util.*", mapOf(
        "LINE" to 1.0,
        "BRANCH" to 1.0,
        "CLASS" to 1.0,
        "INSTRUCTION" to 1.0,
        "METHOD" to 1.0
    ))
}
