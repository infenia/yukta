// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    java
    id("info.solidsoft.pitest")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Applied only with -Pmutation (see spring-conventions). `./gradlew :core:pitest -Pmutation` writes
// build/reports/pitest/. -PmutationTargets=<comma separated class globs> limits the classes mutated.
pitest {
    pitestVersion.set(libs.findVersion("pitest").get().requiredVersion)
    junit5PluginVersion.set(libs.findVersion("pitestJunit5").get().requiredVersion)
    targetClasses.set(
        providers.gradleProperty("mutationTargets").map { it.split(",") }.orElse(listOf("com.infenia.yukta.*"))
    )
    threads.set((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1))
    outputFormats.set(listOf("XML", "HTML"))
    timestampedReports.set(false)
    // Lombok marks what it generates; mutating it only produces noise.
    excludedMethods.set(listOf("toString", "hashCode", "equals"))
    failWhenNoMutations.set(false)
}

// Too slow or too dependent on containers to mutate: the acceptance tests of boot drive the whole
// application, the other two need Docker. Their logic is mutated through the modules above.
if (path in setOf(":boot", ":plus:persistence-postgres", ":plus:workers", ":test-support")) {
    tasks.named("pitest") { enabled = false }
}
