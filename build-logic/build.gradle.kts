// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
buildscript {
  dependencies {
    constraints {
      // GHSA-r937-wjx7-w2jp (CVE-2026-53914): kotlin-dsl pulls kotlin-gradle-plugin 2.4.10
      classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
  }
}

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
    implementation("org.gradle:test-retry-gradle-plugin:${libs.versions.testRetry.get()}")
    implementation("info.solidsoft.gradle.pitest:gradle-pitest-plugin:${libs.versions.pitestPlugin.get()}")

    // Build-time transitives of the plugins above with published CVEs (Dependabot); same minor line
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:2.22.3")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
        implementation("tools.jackson.core:jackson-core:3.2.3")
        implementation("tools.jackson.core:jackson-databind:3.2.3")
        implementation("org.codehaus.plexus:plexus-utils:3.6.1")
        // GHSA-xxph-c9ww-hj94: Guava <= 33.7.1 deserialization allocates without bound
        implementation("com.google.guava:guava:33.7.2-jre")
    }
}
