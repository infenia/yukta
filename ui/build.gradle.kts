// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import com.github.gradle.node.pnpm.task.PnpmTask

plugins {
    id("com.infenia.yukta.spring-conventions")
    id("com.infenia.yukta.node-conventions")
    alias(libs.plugins.spring.boot)
}

coverageConfig {
    val noCoverage = mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    )
    exceptions.put("com.infenia.yukta.ui.*", noCoverage)
}

dependencies {
    implementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.webflux.test)
}

// React/Vite SPA build task
val pnpmBuild = tasks.register<PnpmTask>("pnpmBuild") {
    description = "Build the React SPA (Vite production build)"
    dependsOn("pnpmInstall")
    workingDir.set(layout.projectDirectory)
    pnpmCommand.set(listOf("run", "build"))
    inputs.dir(layout.projectDirectory.dir("src"))
    inputs.dir(layout.projectDirectory.dir("public"))
    inputs.file(layout.projectDirectory.file("package.json"))
    inputs.file(layout.projectDirectory.file("vite.config.ts"))
    inputs.file(layout.projectDirectory.file("index.html"))
    inputs.file(layout.projectDirectory.file("tsconfig.json"))
    inputs.file(layout.projectDirectory.file("tsconfig.node.json"))
    inputs.file(layout.projectDirectory.file("pnpm-lock.yaml"))
    outputs.dir(layout.projectDirectory.dir("dist"))
}

// Wire SPA build into processResources to include dist/ in JAR
tasks.named<ProcessResources>("processResources") {
    dependsOn(pnpmBuild)
    from(layout.projectDirectory.dir("dist")) {
        into("static")
    }
}

// Validate SPDX headers in source files
val validateHeaders = tasks.register<PnpmTask>("validateHeaders") {
    description = "Validate SPDX license headers in UI source files"
    dependsOn("pnpmInstall")
    workingDir.set(layout.projectDirectory)
    pnpmCommand.set(listOf("run", "lint:headers"))
}

// Unit & component tests
val pnpmTest = tasks.register<PnpmTask>("pnpmTest") {
    description = "Run Vitest unit/component tests"
    dependsOn("pnpmInstall")
    workingDir.set(layout.projectDirectory)
    pnpmCommand.set(listOf("run", "test", "--", "--run"))
}

// E2E tests (Playwright) — gated behind a Gradle property
val pnpmTestE2e = tasks.register<PnpmTask>("pnpmTestE2e") {
    description = "Run Playwright E2E tests"
    dependsOn("pnpmInstall", pnpmBuild)
    workingDir.set(layout.projectDirectory)
    pnpmCommand.set(listOf("exec", "playwright", "test"))
    onlyIf {
        project.hasProperty("runE2e") && project.property("runE2e") == "true"
    }
}

// Wire header validation and tests into Gradle's check task
tasks.named("check") {
    dependsOn(validateHeaders)
    // Uncomment when tests are written:
    // dependsOn(pnpmTest)
}

// Disable Spring Boot JAR/bootRun on this module
tasks.named("bootJar") {
    enabled = false
}

tasks.named("bootRun") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

tasks.named<Test>("test") {
    failOnNoDiscoveredTests.set(false)
}

// NOTE: Spotless is NOT applied to UI module to avoid conflicts with Prettier.
// The UI module uses Prettier (pnpm format) for code formatting and license headers are
// managed manually. See .prettierignore for files excluded from formatting checks.

// Disable npm and yarn tasks — only pnpm is used
tasks.withType<com.github.gradle.node.npm.task.NpmSetupTask>().configureEach {
    enabled = false
}
tasks.withType<com.github.gradle.node.npm.task.NpmInstallTask>().configureEach {
    enabled = false
}
tasks.withType<com.github.gradle.node.yarn.task.YarnSetupTask>().configureEach {
    enabled = false
}
tasks.withType<com.github.gradle.node.yarn.task.YarnTask>().configureEach {
    enabled = false
}
