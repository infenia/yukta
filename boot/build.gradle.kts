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
    id("com.infenia.yukta.spring-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.buildtools.native)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":web"))
    implementation(project(":mcp"))
    implementation(project(":ui"))
    implementation(project(":plugins:processors:internal:internal-core"))
    implementation(project(":plugins:triggers:api-trigger"))
    implementation(project(":plugins:triggers:constant-source"))
    implementation(project(":plugins:terminals:console-terminal"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    developmentOnly(libs.spring.boot.devtools)
    developmentOnly(libs.spring.boot.docker.compose)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("com.infenia.yukta.YuktaApplication")
    args("--spring.profiles.active=dev")
    standardInput = System.`in`
    // Ensure the application always starts even if no files changed
    outputs.upToDateWhen { false }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("yukta")
            mainClass.set("com.infenia.yukta.YuktaApplication")
            buildArgs.add("--no-fallback")

            // 🔥 Force prod profile inside native image
            buildArgs.add("-Dspring.profiles.active=prod")
        }
    }
}
