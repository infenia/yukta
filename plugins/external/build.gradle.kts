// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// Community plugins: processors and terminals that run outside the server, described by a
// yukta-plugin.json manifest in yukta.plugins.dir (design/plans/community-plugins.md).
dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    // runs the plugin commands (ModuleRules.ALLOWED: external>process-executor)
    implementation(project(":plugins:processors:process-executor"))
    // reaches run.mcp plugins over HTTP (ModuleRules.ALLOWED: external>mcp-tool-call)
    implementation(project(":plugins:processors:mcp-tool-call"))
    implementation(libs.jackson.databind)
    // validates manifests and plugin output against the plugin-api schemas
    implementation(libs.mcp.json.jackson3)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}

coverageConfig {
}
