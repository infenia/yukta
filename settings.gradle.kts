rootProject.name = "yukta"

includeBuild("build-logic")

include("plugin-api")
include("plugins:build-tools:common")
include("plugins:build-tools:gradle")
include("plugins:processors:internal:internal-core")
include("plugins:processors:scripting")
include("plugins:triggers:api-trigger")
include("plugins:triggers:constant-source")
include("plugins:terminals:console-terminal")
include("core")
include("web")
include("mcp")
include("ui")
include("boot")
