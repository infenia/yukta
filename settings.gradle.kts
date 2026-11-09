// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    // Downloads a missing JDK for the Java toolchain instead of failing the build
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yukta"

includeBuild("build-logic")

include("messaging")
include("test-support")
include("plugin-api")
include("plugins:processors:process-executor")
include("plugins:processors:http-request")
include("plugins:processors:mcp-tool-call")
include("plugins:processors:file-io")
include("plugins:processors:sql-query")
include("plugins:processors:llm-call")
include("plugins:processors:session-memory")
include("plugins:processors:decision")
include("plugins:processors:internal:internal-core")
include("plugins:processors:internal:script-js")
include("plugins:triggers:api-trigger")
include("plugins:triggers:constant-source")
include("plugins:triggers:schedule-trigger")
include("plugins:triggers:webhook-trigger")
include("plugins:terminals:console-terminal")
include("plugins:terminals:notify-terminal")
include("plugins:terminals:outcome-terminal")
include("plugins:supervisors:rules")
include("plugins:supervisors:callout")
include("plugins:supervisors:process-engine")
include("core")
include("plus")
include("plus:persistence-postgres")
include("plus:workers")
include("plus:plus-architecture")
include("web")
include("cli")
include("mcp")
include("ui")
include("boot")
include("architecture")
