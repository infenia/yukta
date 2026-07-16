// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
    implementation(project(":plugins:triggers:oneshot-trigger"))
    implementation(project(":plugins:terminals:console-terminal"))
    implementation(project(":plugins:processors:process-executor"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Dev-only dependencies (automatically excluded from native image but made explicit for clarity)
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
}

coverageConfig {
    exceptions.put("com.infenia.yukta.YuktaApplication", mapOf(
        "LINE" to 0.70,
        "BRANCH" to 0.60,
        "INSTRUCTION" to 0.75,
        "METHOD" to 0.70,
    ))
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("yukta")
            mainClass.set("com.infenia.yukta.YuktaApplication")
            buildArgs.add("--no-fallback")

            // Increase heap for native image compilation (JTE classes need more memory)
            buildArgs.add("-J-Xmx16g")

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
