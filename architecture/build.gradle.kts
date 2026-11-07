// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// Test-only module: it holds the ArchUnit rules and needs every Java module on its classpath.
dependencies {
    testImplementation(project(":messaging"))
    testImplementation(project(":plugin-api"))
    testImplementation(project(":core"))
    testImplementation(project(":web"))
    testImplementation(project(":mcp"))
    testImplementation(project(":plugins:processors:process-executor"))
    testImplementation(project(":plugins:processors:http-request"))
    testImplementation(project(":plugins:processors:mcp-tool-call"))
    testImplementation(project(":plugins:processors:file-io"))
    testImplementation(project(":plugins:processors:sql-query"))
    testImplementation(project(":plugins:processors:llm-call"))
    testImplementation(project(":plugins:processors:session-memory"))
    testImplementation(project(":plugins:processors:decision"))
    testImplementation(project(":plugins:processors:internal:internal-core"))
    testImplementation(project(":plugins:processors:internal:script-js"))
    testImplementation(project(":plugins:triggers:api-trigger"))
    testImplementation(project(":plugins:triggers:constant-source"))
    testImplementation(project(":plugins:triggers:schedule-trigger"))
    testImplementation(project(":plugins:triggers:webhook-trigger"))
    testImplementation(project(":plugins:terminals:console-terminal"))
    testImplementation(project(":plugins:terminals:notify-terminal"))
    testImplementation(project(":plugins:terminals:outcome-terminal"))
    testImplementation(project(":plugins:supervisors:rule-supervisor"))
    testImplementation(project(":plugins:supervisors:guards"))
    testImplementation(project(":plugins:supervisors:webhook"))
    testImplementation(project(":plugins:control-planes:rule-control-plane"))
    testImplementation(project(":plugins:control-planes:model-control-plane"))
    testImplementation(project(":plugins:control-planes:rules-first-control-plane"))
    testImplementation(libs.archunit.junit5)
}
