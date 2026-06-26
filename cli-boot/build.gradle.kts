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
    implementation(project(":cli"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.picocli)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.webflux.test)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("com.infenia.yukta.cli.YuktaCliApplication")
    standardInput = System.`in`
    // Ensure the application always starts even if no files changed
    outputs.upToDateWhen { false }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("yukta-cli")
            mainClass.set("com.infenia.yukta.cli.YuktaCliApplication")
            buildArgs.add("--no-fallback")

            // Increase heap for native image compilation
            buildArgs.add("-J-Xmx8g")

            // Size-reduction flags (compatible with GraalVM 25)
            buildArgs.add("-H:+RemoveUnusedSymbols")           // Remove unused symbols
            buildArgs.add("-H:+StripDebugInfo")                // Remove debug information
            buildArgs.add("-H:-AddAllCharsets")                // Remove unnecessary charsets

            // Force prod profile + AOT compilation
            buildArgs.add("-Dspring.profiles.active=prod")
            buildArgs.add("-Dspring.aot.enabled=true")
        }
    }
}
