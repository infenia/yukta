// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    base
    alias(libs.plugins.spotless)
}

// The convention scripts and root scripts decide every rule, so they get the same header and
// format check as the product code (the per-module spotless target cannot reach build-logic).
spotless {
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        targetExclude("**/build/**", "**/bin/**")
        licenseHeaderFile(rootProject.file("config/license/header.txt"), "[^/\\s]")
    }
}

allprojects {
    group = "com.infenia.yukta"
}

// On-demand only (not part of `check`); CI (security.yml) owns scheduled scanning.
tasks.register<Exec>("trivy") {
    description = "Scan the repository for HIGH/CRITICAL vulnerabilities with Trivy (on demand)"
    group = "verification"
    commandLine("trivy", "fs", "--severity", "HIGH,CRITICAL", "--exit-code", "1",
        "--skip-dirs", "**/node_modules", "--skip-dirs", "**/build", projectDir.absolutePath)
}
