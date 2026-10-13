// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:${libs.versions.spotbugs.get()}")
    implementation("io.freefair.gradle:lombok-plugin:${libs.versions.lombok.get()}")
    implementation("com.github.node-gradle:gradle-node-plugin:${libs.versions.nodeGradle.get()}")
    implementation("io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:${libs.versions.springDependencyManagement.get()}")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:${libs.versions.errorpronePlugin.get()}")
    implementation("app.cash.licensee:licensee-gradle-plugin:${libs.versions.licensee.get()}")

    // Build-time transitives of the plugins above with published CVEs (Dependabot); same minor line
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.7")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.21.7")
        implementation("tools.jackson.core:jackson-core:3.1.7")
        implementation("tools.jackson.core:jackson-databind:3.1.7")
        implementation("org.codehaus.plexus:plexus-utils:3.6.1")
    }
}
