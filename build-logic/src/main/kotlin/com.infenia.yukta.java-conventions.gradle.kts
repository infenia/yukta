// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    java
    id("io.freefair.lombok")
    id("org.gradle.test-retry")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.infenia.yukta"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
    options.release.set(25)
}

// Same sources must give byte-identical archives (OpenSSF build_repeatable).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    jvmArgs("-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1")
    failFast = providers.gradleProperty("failFast").isPresent
    // Nightly CI only (-PretryFlakyTests): re-run a failed test up to twice so the report can tell a
    // flaky test (passed on retry) from a broken one. The task still fails either way.
    if (providers.gradleProperty("retryFlakyTests").isPresent) {
        extensions.configure<org.gradle.testretry.TestRetryTaskExtension> {
            maxRetries.set(2)
            maxFailures.set(20)
            failOnPassedAfterRetry.set(true)
        }
    }
}
