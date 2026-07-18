// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import com.github.gradle.node.NodeExtension

plugins {
    id("com.github.node-gradle.node")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<NodeExtension> {
    // Version of node to use.
    version.set(libs.findVersion("node").get().requiredVersion)

    // Version of pnpm to use.
    pnpmVersion.set(libs.findVersion("pnpm").get().requiredVersion)

    // If true, it will download node using above parameters.
    // If false, it will try to use globally installed node.
    // In CI (GitHub Actions), use system Node.js; locally use downloaded version.
    download.set(System.getenv("CI") == null)
}

// Disable npm and yarn tasks — only pnpm is used in this project
tasks.withType<com.github.gradle.node.npm.task.NpmTask>().configureEach {
    enabled = false
}
tasks.withType<com.github.gradle.node.yarn.task.YarnTask>().configureEach {
    enabled = false
}
