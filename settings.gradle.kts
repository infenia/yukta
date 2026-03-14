rootProject.name = "yukta"

includeBuild("build-logic")

include("yukta-plugin-api")
include("plugins:build-tools:common")
include("plugins:build-tools:gradle")
include("plugins:processors:internal:core")
include("plugins:triggers:api-trigger")
include("plugins:triggers:constant-source")
include("plugins:terminals:console-terminal")
include("yukta-core")
include("yukta-web")
include("yukta-mcp")
include("yukta-ui")
include("yukta-boot")
