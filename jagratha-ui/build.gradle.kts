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
import gg.jte.gradle.JteExtension
import com.github.gradle.node.pnpm.task.PnpmTask
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.infenia.jagratha.java-conventions")
    id("com.infenia.jagratha.quality-conventions")
    id("com.infenia.jagratha.jacoco-conventions")
    id("com.infenia.jagratha.node-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.jte)
}

extra["jacocoMinimumCoverage"] = 0.05

dependencies {
    implementation(project(":jagratha-core"))
    implementation(libs.jte.starter)
    implementation(libs.htmx.spring.boot)
    implementation(libs.spring.boot.starter.webflux)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.reactor.test)
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
        binaryStaticContent.set(true)
    } else {
        println("JTE → DEVELOPMENT mode (hot reload)")
        generate()
        binaryStaticContent.set(false)
    }
}

tasks.named("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

// Tailwind CSS task
val tailwind = tasks.register<PnpmTask>("tailwind") {
    dependsOn("pnpmInstall")
    pnpmCommand.set(listOf("exec", "tailwindcss", "-i", "./src/main/resources/static/css/input.css", "-o", "${layout.buildDirectory.get().asFile}/tailwind/style.css", "--minify"))
    inputs.file("./src/main/resources/static/css/input.css")
    outputs.file(layout.buildDirectory.file("tailwind/style.css"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(tailwind)
    from(layout.buildDirectory.dir("tailwind")) {
        into("static/css")
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    onlyIf { tasks.named("test").get().didWork || layout.buildDirectory.file("jacoco/test.exec").get().asFile.exists() }
}
