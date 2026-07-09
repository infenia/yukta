// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import gg.jte.gradle.JteExtension
import com.github.gradle.node.pnpm.task.PnpmTask
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.infenia.yukta.spring-conventions")
    id("com.infenia.yukta.node-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jte)
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
    exceptions.put("gg.jte.generated.**", mapOf(
        "LINE" to 0.0,
        "BRANCH" to 0.0,
        "CLASS" to 0.0,
        "INSTRUCTION" to 0.0,
        "METHOD" to 0.0
    ))
}

dependencies {
    implementation(project(":core"))
    implementation(project(":web"))
    implementation(libs.jte.starter)
    implementation(libs.htmx.spring.boot)
    implementation(libs.spring.boot.starter.webflux)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.playwright)
}

val requestedTasks = gradle.startParameter.taskNames

val isNativeBuild = requestedTasks.any {
    it.contains("native", ignoreCase = true)
}

val isPackagingBuild = requestedTasks.any {
    it.contains("build", ignoreCase = true) ||
            it.contains("assemble", ignoreCase = true) ||
            it.contains("bootJar", ignoreCase = true)
}

val isProdBuild = isNativeBuild || isPackagingBuild


configure<JteExtension> {
    sourceDirectory.set(
        project.layout.projectDirectory
            .dir("src/main/jte")
            .asFile
            .toPath()
    )

    targetDirectory.set(
        layout.buildDirectory
            .dir("jte-classes")
            .get()
            .asFile
            .toPath()
    )
    compilePath = sourceSets.main.get().compileClasspath
    if (isProdBuild) {
        println("JTE → PRODUCTION mode (precompiled)")
        precompile()
        // Disable binary static content for native images: BinaryContent.load() uses
        // ClassLoader.getResourceAsStream() which doesn't work reliably in GraalVM native images.
        // Text-based content works fine and adds minimal overhead.
        binaryStaticContent.set(false)
    } else {
        println("JTE → DEVELOPMENT mode (hot reload)")
        generate()
        binaryStaticContent.set(false)
    }
}

// Add generated JTE source directory to Java compilation
sourceSets.main.get().java.srcDir(layout.buildDirectory.dir("jte-classes"))

// Copy JTE precompiled classes to classes output dir so they're included in JAR
tasks.register<Copy>("copyJteClasses") {
    description = "Copy JTE Classes"
    dependsOn(tasks.named("precompileJte"), tasks.named("generateJte"))
    from(layout.buildDirectory.dir("jte-classes"))
    into(layout.buildDirectory.dir("classes/java/main"))
}

tasks.named("classes") {
    dependsOn(tasks.named("copyJteClasses"))
}

// Fix dependency: Checkstyle should depend on JTE generation
tasks.named("checkstyleMain") {
    dependsOn(tasks.named("generateJte"), tasks.named("precompileJte"))
}

tasks.named("bootJar") {
    enabled = false
}

tasks.named("bootRun") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

// Tailwind CSS task
val tailwind = tasks.register<PnpmTask>("tailwind") {
    description = "Tailwind"
    dependsOn("pnpmInstall")
    pnpmCommand.set(listOf("exec", "tailwindcss", "-i", "./src/main/resources/static/css/input.css", "-o", "${layout.buildDirectory.get().asFile}/tailwind/style.css", "--minify"))
    inputs.file("./src/main/resources/static/css/input.css")
    outputs.file(layout.buildDirectory.file("tailwind/style.css"))
}

// JS Bundle task
val bundleJs = tasks.register<PnpmTask>("bundleJs") {
    description = "Bundle JS"
    dependsOn("pnpmInstall")
    pnpmCommand.set(listOf("exec", "esbuild", "./src/main/js/app.js", "--bundle", "--outfile=${layout.buildDirectory.get().asFile}/esbuild/app.js", "--minify", "--sourcemap"))
    inputs.dir("./src/main/js")
    outputs.dir(layout.buildDirectory.dir("esbuild"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(tailwind, bundleJs, tasks.named("precompileJte"), tasks.named("generateJte"))
    from(layout.buildDirectory.dir("tailwind")) {
        into("static/css")
    }
    from(layout.buildDirectory.dir("esbuild")) {
        into("static/js")
    }
    // JTE plugin automatically includes binary content files (.bin) in generated-resources/jte
    // which are picked up by processResources. No need to explicitly copy them.
}

tasks.named<JacocoReport>("jacocoTestReport") {
    onlyIf {
        // Check if jacoco execution data exists using a provider to avoid configuration cache issues
        executionData.files.any { it.exists() }
    }
}

tasks.named("pmdMain") {
    dependsOn(tasks.named("precompileJte"))
}

tasks.withType<Pmd> {
    exclude("**/generated/**")
}

tasks.withType<Checkstyle> {
    exclude("**/generated/**")
}

tasks.named<Test>("test") {
    failOnNoDiscoveredTests.set(false)
}
