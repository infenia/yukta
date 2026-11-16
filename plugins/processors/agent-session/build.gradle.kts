// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// AGENT_SESSION: runs an AI agent harness and records the conversation. It starts the agent through
// the process executor's gateway (ModuleRules.ALLOWED: agent-session>process-executor).
dependencies {
    api(project(":messaging"))
    implementation(project(":plugin-api"))
    implementation(project(":plugins:processors:process-executor"))
    implementation(libs.spring.boot.starter.webflux)
}

coverageConfig {
}
