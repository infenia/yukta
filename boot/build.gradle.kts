// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.spring-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.buildtools.native)
}

// Optional plugins are chosen with -Pyukta.plugins=default (all except script-js), all, none, or a comma-separated list
// of: process-executor, http-request, file-io, sql-query, llm-call, session-memory, mcp-tool-call, decision, script-js.
val optionalPlugins = mapOf(
    "process-executor" to ":plugins:processors:process-executor",
    "http-request" to ":plugins:processors:http-request",
    "file-io" to ":plugins:processors:file-io",
    "sql-query" to ":plugins:processors:sql-query",
    "llm-call" to ":plugins:processors:llm-call",
    "session-memory" to ":plugins:processors:session-memory",
    "decision" to ":plugins:processors:decision",
    "mcp-tool-call" to ":plugins:processors:mcp-tool-call",
    "script-js" to ":plugins:processors:internal:script-js",
)
// Default leaves out script-js (GraalJS/Truffle is the largest part of the native image);
// ask for it with -Pyukta.plugins=all or by name.
val requested = providers.gradleProperty("yukta.plugins").orNull?.trim() ?: "default"
val selectedPlugins = when (requested) {
    "default" -> optionalPlugins.keys - "script-js"
    "all" -> optionalPlugins.keys
    "none", "" -> emptySet()
    else -> requested.split(",").map { it.trim() }.toSet()
}
val unknownPlugins = selectedPlugins - optionalPlugins.keys
require(unknownPlugins.isEmpty()) {
    "Unknown yukta.plugins entries $unknownPlugins; valid: ${optionalPlugins.keys}"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":web"))
    implementation(project(":mcp"))
    implementation(project(":ui"))
    implementation(project(":plugins:processors:internal:internal-core"))
    implementation(project(":plugins:triggers:api-trigger"))
    implementation(project(":plugins:triggers:constant-source"))
    implementation(project(":plugins:triggers:schedule-trigger"))
    implementation(project(":plugins:terminals:notify-terminal"))
    implementation(project(":plugins:terminals:console-terminal"))
    implementation(project(":plugins:supervisors:rule-supervisor"))
    implementation(project(":plugins:control-planes:rule-control-plane"))
    implementation(project(":plugins:control-planes:model-control-plane"))
    implementation(project(":plugins:control-planes:rules-first-control-plane"))

    selectedPlugins.forEach { implementation(project(optionalPlugins.getValue(it))) }

    // PostgreSQL stores for sessions, workflows and the execution log: -Pyukta.persistence=postgres
    if (providers.gradleProperty("yukta.persistence").orNull == "postgres") {
        implementation(project(":persistence-postgres"))
    }

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Dev-only dependencies (automatically excluded from native image but made explicit for clarity)
    developmentOnly(libs.spring.boot.devtools)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

// Tells the tests whether the PostgreSQL stores are part of this build.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("yukta.persistence").orNull?.let { systemProperty("yukta.persistence", it) }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("com.infenia.yukta.YuktaApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("yukta")
            mainClass.set("com.infenia.yukta.YuktaApplication")
            buildArgs.add("--no-fallback")
            buildArgs.add("--emit=build-report")

            // Heap for native image compilation
            buildArgs.add("-J-Xmx16g")

            // Size-reduction flags (compatible with GraalVM 25)
            buildArgs.add("-H:+RemoveUnusedSymbols")           // Remove unused symbols
            buildArgs.add("-H:+StripDebugInfo")                // Remove debug information
            buildArgs.add("-H:-AddAllCharsets")                // Remove unnecessary charsets
            buildArgs.add("-H:IncludeLocales=en")              // English is the only supported locale

            // Dev builds: -Pnative.quick trades runtime speed for much faster image compilation
            if (providers.gradleProperty("native.quick").isPresent) buildArgs.add("-Ob")

            // Force prod profile + AOT compilation
            buildArgs.add("-Dspring.profiles.active=prod")
            buildArgs.add("-Dspring.aot.enabled=true")
        }
    }
}
