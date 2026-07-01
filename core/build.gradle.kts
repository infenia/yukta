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
    api(project(":plugin-api"))
    api(project(":messaging"))

    // Default plugins

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.mapstruct)
    implementation(libs.caffeine)

    implementation(libs.springdoc.openapi)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok.mapstruct.binding)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.webclient.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.jackson.test)
    testImplementation(libs.awaitility)
}

// Coverage exceptions for modified/new components in this refactoring
coverageConfig {
    exceptions.put("*WorkflowValidator", mapOf("LINE" to 0.97, "BRANCH" to 0.98, "INSTRUCTION" to 0.98, "METHOD" to 0.96))
    exceptions.put("*FileSessionConfigStore", mapOf("BRANCH" to 0.95, "INSTRUCTION" to 0.99))
    exceptions.put("*WorkflowCompiler", mapOf("BRANCH" to 0.96))
    exceptions.put("*TerminalNodeAssemblerStrategy", mapOf("INSTRUCTION" to 0.99, "METHOD" to 0.90))
    exceptions.put("*WorkflowOrchestrator", mapOf("BRANCH" to 0.87, "INSTRUCTION" to 0.96, "METHOD" to 0.86))
    exceptions.put("*StreamTopologyDecorator", mapOf("LINE" to 0.98, "INSTRUCTION" to 0.99, "METHOD" to 0.94))
    exceptions.put("*WorkflowState", mapOf("BRANCH" to 0.91))
    exceptions.put("*DefaultStatusHistoryCache", mapOf("LINE" to 0.86, "INSTRUCTION" to 0.91))
    exceptions.put("*DefaultTaskTrackerService", mapOf("LINE" to 0.96, "BRANCH" to 0.84, "INSTRUCTION" to 0.96, "METHOD" to 0.96))
    exceptions.put("*DirectiveDispatcher", mapOf("LINE" to 0.87, "INSTRUCTION" to 0.88, "METHOD" to 0.82))
    exceptions.put("*ControlBusService", mapOf("LINE" to 0.92, "BRANCH" to 0.96, "INSTRUCTION" to 0.91, "METHOD" to 0.97))
    exceptions.put("*DefaultControlBusGateway", mapOf("LINE" to 0.98, "INSTRUCTION" to 0.98))
}
