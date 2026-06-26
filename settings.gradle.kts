rootProject.name = "yukta"

includeBuild("build-logic")

include("messaging")
include("plugin-api")
include("plugins:processors:process-executor")
include("plugins:processors:internal:internal-core")
include("plugins:triggers:api-trigger")
include("plugins:triggers:constant-source")
include("plugins:triggers:auto-trigger")
include("plugins:terminals:console-terminal")
include("core")
include("web")
include("cli")
include("cli-boot")
include("mcp")
include("ui")
include("boot")
