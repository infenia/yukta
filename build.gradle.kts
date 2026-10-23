// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

plugins {
    base
    alias(libs.plugins.spotless) apply false
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
