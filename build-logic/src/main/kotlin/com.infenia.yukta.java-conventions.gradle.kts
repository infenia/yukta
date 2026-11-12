// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    java
    id("io.freefair.lombok")
    id("org.gradle.test-retry")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

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

// Whether a whole class runs. `--tests` is not visible in the task's filter (Gradle keeps it apart from
// the build-script patterns), so read it from the command line: with it, only some tests of a class run
// and an @ExpectedErrorLog that a skipped test would have used must not be reported as unused.
val partialRun = gradle.startParameter.taskRequests.any { r -> r.args.any { it == "--tests" || it.startsWith("--tests=") } }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    jvmArgs("-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1")
    failFast = providers.gradleProperty("failFast").isPresent
    // test-support registers ErrorGuardExtension through the ServiceLoader. It fails a test that drops a
    // Reactor signal or logs an unexpected ERROR, or that expects one that does not happen;
    // -PerrorGuard=report only reports them, =off turns it off.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("yukta.errorGuard", providers.gradleProperty("errorGuard").getOrElse("strict"))
    systemProperty("yukta.errorGuard.stale", (!partialRun && filter.includePatterns.isEmpty()).toString())
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
