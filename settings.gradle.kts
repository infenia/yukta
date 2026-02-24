rootProject.name = "jagratha"

includeBuild("build-logic")

include("jagratha-plugin-api")
include("plugins:build-tools:common")
include("plugins:build-tools:gradle")
include("plugins:file-update")
include("plugins:processors:internal:core")
include("jagratha-core")
include("jagratha-ui")
include("jagratha-boot")
