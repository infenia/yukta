// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.spring-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.buildtools.native)
}

// /actuator/info reports build.version. No build time, so the file stays the same and the task stays up to date.
springBoot {
    buildInfo {
        excludes.set(setOf("time"))
    }
}

// MCP serverInfo.version follows the project version, not a literal in application.yaml.
tasks.processResources {
    val projectVersion = project.version.toString()
    filesMatching("application.yaml") { filter { it.replace("@project.version@", projectVersion) } }
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
    implementation(project(":plugins:triggers:webhook-trigger"))
    implementation(project(":plugins:terminals:notify-terminal"))
    implementation(project(":plugins:terminals:outcome-terminal"))
    implementation(project(":plugins:terminals:console-terminal"))
    implementation(project(":plugins:supervisors:rule-supervisor"))
    implementation(project(":plugins:control-planes:rule-control-plane"))
    implementation(project(":plugins:control-planes:model-control-plane"))
    implementation(project(":plugins:control-planes:rules-first-control-plane"))

    selectedPlugins.forEach { implementation(project(optionalPlugins.getValue(it))) }

    // PostgreSQL stores for sessions, workflows and the execution log: -Pyukta.persistence=postgres
    if (providers.gradleProperty("yukta.persistence").orNull == "postgres") {
        implementation(project(":plus:persistence-postgres"))
    }

    // Single-node durable storage on disk (AGPL-3.0-only, see LICENSING.md): -Pyukta.edition=plus
    if (providers.gradleProperty("yukta.edition").orNull == "plus") {
        implementation(project(":plus"))
    }

    // Remote workers over RSocket (control plane server and worker agent): -Pyukta.workers=true
    if (providers.gradleProperty("yukta.workers").orNull == "true") {
        implementation(project(":plus:workers"))
    }

    implementation(libs.spring.boot.starter.webflux)
    // interactive console (run mode "console")
    implementation(libs.jline)
    implementation(libs.jackson.databind)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    // /actuator/prometheus for Grafana users; exposed only when "prometheus" is in management.endpoints.web.exposure.include
    implementation(libs.micrometer.registry.prometheus)
    // OTLP export of traces and metrics; off until management.otlp.*.endpoint/url is set (application.yaml)
    implementation(libs.spring.boot.starter.opentelemetry)

    // Dev-only dependencies (automatically excluded from native image but made explicit for clarity)
    developmentOnly(libs.spring.boot.devtools)

    annotationProcessor(libs.spring.boot.configuration.processor)

    // WorkflowAcceptanceTest and SupervisorAcceptanceTest run PROCESS_EXECUTOR whatever -Pyukta.plugins says
    testImplementation(project(":plugins:processors:process-executor"))
    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.h2)
}

// Tells the tests whether the optional modules (PostgreSQL stores, remote workers) are part of this build.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("yukta.persistence").orNull?.let { systemProperty("yukta.persistence", it) }
    providers.gradleProperty("yukta.edition").orNull?.let { systemProperty("yukta.edition", it) }
    providers.gradleProperty("yukta.workers").orNull?.let { systemProperty("yukta.workers", it) }
    // BoundaryFuzzAcceptanceTest: -PfuzzTries=<cases per entry point>, -PfuzzSeed=<number>|random (nightly)
    providers.gradleProperty("fuzzTries").orNull?.let { systemProperty("yukta.fuzz.tries", it) }
    providers.gradleProperty("fuzzSeed").orNull?.let { systemProperty("yukta.fuzz.seed", it) }
    // DagPropertyAcceptanceTest: -PdagTries=<cases per property>, -PdagSeed=<number>|random (nightly)
    providers.gradleProperty("dagTries").orNull?.let { systemProperty("yukta.dag.tries", it) }
    providers.gradleProperty("dagSeed").orNull?.let { systemProperty("yukta.dag.seed", it) }
    providers.gradleProperty("yukta.plugins").orNull?.let { systemProperty("yukta.plugins", it) }
    providers.gradleProperty("yukta.bean-inventory.update").orNull?.let { systemProperty("yukta.bean-inventory.update", it) }
    providers.gradleProperty("yukta.openapi.update").orNull?.let { systemProperty("yukta.openapi.update", it) }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("com.infenia.yukta.YuktaApplication")
    // the default profile is prod; developers get dev unless they name one
    if (System.getProperty("spring.profiles.active") == null) {
        systemProperty("spring.profiles.active", "dev")
    }
}

// Spring decides property-conditioned beans when the native image is built (AOT), not when it
// starts. So a native image with the workers module needs to be built as the role it will have:
//   -Pyukta.workers.native=control-plane  serves workers (set yukta.workers.token and
//                                         spring.rsocket.server.port when it starts)
//   -Pyukta.workers.native=worker         is a worker (set yukta.workers.control-plane, .token, .id,
//                                         .plugin-types when it starts)
// The values given here only switch the beans on; the real ones are read at start.
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
    when (providers.gradleProperty("yukta.workers.native").orNull) {
        "control-plane" -> args("--yukta.workers.token=set-at-start", "--spring.rsocket.server.port=0")
        "worker" ->
            args(
                "--yukta.workers.control-plane=set-at-start:0",
                "--yukta.workers.token=set-at-start",
                "--yukta.workers.id=set-at-start",
            )
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("yukta")
            mainClass.set("com.infenia.yukta.YuktaApplication")
            buildArgs.add("--no-fallback")

            // openai-java-core ships agent-recorded proxy/serialization metadata captured from its
            // own Gradle/ByteBuddy-based tests (classes absent here), so native-image warns
            // "Cannot register dynamic proxy" / "Could not resolve ... for serialization".
            buildArgs.add("--exclude-config")
            buildArgs.add(".*openai-java-core.*\\.jar")
            buildArgs.add("^/META-INF/native-image/(proxy|serialization)-config\\.json$")

            // micrometer-core's proxy-config.json only covers JMS instrumentation (unused here) and
            // triggers the deprecated DynamicProxyConfigurationResources warning.
            buildArgs.add("--exclude-config")
            buildArgs.add(".*micrometer-core.*\\.jar")
            buildArgs.add("^/META-INF/native-image/.*/proxy-config\\.json$")

            // json-schema-validator's native-image.properties injects experimental -H: options (its
            // empty reflect-config and resource patterns live in our own native-image metadata).
            buildArgs.add("--exclude-config")
            buildArgs.add(".*json-schema-validator.*\\.jar")
            buildArgs.add("^/META-INF/native-image/.*/native-image\\.properties$")

            // Our -H:+... flags are experimental in GraalVM 25.
            buildArgs.add("-H:+UnlockExperimentalVMOptions")

            // Heap for native image compilation
            // -Pnative.heap=<size> lowers it for machines with less memory (default 16g)
            buildArgs.add("-J-Xmx${providers.gradleProperty("native.heap").getOrElse("16g")}")
            // Netty loads its native library; without this the binary prints four "restricted method" WARNING lines at every start.
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            // Builder JVM has slf4j-api but no provider; its NOP fallback warning is harmless
            buildArgs.add("-J-Dslf4j.internal.verbosity=ERROR")

            // Size-reduction flags (compatible with GraalVM 25)
            buildArgs.add("-H:+RemoveUnusedSymbols")           // Remove unused symbols
            buildArgs.add("-H:+StripDebugInfo")                // Remove debug information
            buildArgs.add("-H:-AddAllCharsets")                // Remove unnecessary charsets
            buildArgs.add("-H:IncludeLocales=en")              // English is the only supported locale

            // Docker builds: -Pnative.static links zlib and everything except libc into the binary, so
            // it runs on the distroless cc image (which has libc but no libz). Needs libz.a.
            if (providers.gradleProperty("native.static").isPresent) buildArgs.add("--static-nolibc")

            // Optimization level (-O3, --pgo and --pgo-instrument are Oracle GraalVM features; -march=compatibility
            // (below) keeps release binaries runnable on any x86-64 / arm64 CPU; the default is x86-64-v3):
            //   -Pnative.quick          -Ob, much faster image compilation, slower at run time
            //   -Pnative.pgoInstrument  instrumented binary that records a profile (release/pgo-profile.sh)
            //   default                 -O3 with the committed boot/pgo/yukta.iprof; without that file
            //                           Oracle GraalVM infers a profile itself (GraalNN)
            buildArgs.add("-march=compatibility")
            val pgoProfile = layout.projectDirectory.file("pgo/yukta.iprof").asFile
            when {
                providers.gradleProperty("native.quick").isPresent -> buildArgs.add("-Ob")
                providers.gradleProperty("native.pgoInstrument").isPresent -> buildArgs.addAll("-O3", "--pgo-instrument")
                else -> {
                    buildArgs.add("-O3")
                    if (pgoProfile.exists()) buildArgs.add("--pgo=${pgoProfile.absolutePath}")
                }
            }

            // Force prod profile + AOT compilation
            buildArgs.add("-Dspring.profiles.active=prod")
            buildArgs.add("-Dspring.aot.enabled=true")
        }
    }
}

// The catalog's Netty version must be what ships (CVE fixes ride on it): fail if the runtime
// classpath resolves any io.netty artifact to another version.
val verifyNettyVersion by tasks.registering {
    description = "Fails when a resolved io.netty artifact differs from the version catalog"
    val expected = libs.versions.netty.get()
    val versions = configurations.runtimeClasspath.map { cfg ->
        cfg.incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "io.netty" && it.name != "netty-tcnative-classes" && !it.name.startsWith("netty-tcnative") }
            .filter { it.version != expected }
            .map { "${it.group}:${it.name}:${it.version}" }
    }
    doLast {
        val wrong = versions.get()
        check(wrong.isEmpty()) { "Netty must resolve to $expected (gradle/libs.versions.toml); found $wrong" }
    }
}
tasks.named("check") { dependsOn(verifyNettyVersion) }

// Spring AOT freezes @ConditionalOnProperty at build time, so a stray YUKTA_* variable in the build
// environment (the dev server sets YUKTA_SUPERVISOR_RULES_FILE) would bake that bean into the native
// binary and break its start. Build from a clean environment.
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
    environment.keys.filter { it.startsWith("YUKTA_") || it.startsWith("SPRING_") }.forEach { environment.remove(it) }
}
