// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
  id("base")
}

// All build logic lives in the Makefile (tool versions are pinned there); Gradle only
// delegates, so compiler/linter output is shown as-is and there is one place to change.
fun makeTask(name: String, target: String, taskGroup: String, desc: String) =
  tasks.register<Exec>(name) {
    group = taskGroup
    description = desc
    workingDir = projectDir
    commandLine("make", "--no-print-directory", target)
  }

makeTask("goFormat", "fmt", "formatting", "Format Go source code using gofmt")
makeTask("goFormatCheck", "fmt-check", "verification", "Validate Go source formatting")
makeTask("goModTidy", "mod-tidy", "go", "Tidy Go module dependencies")
makeTask("goModVerify", "mod-verify", "verification", "Verify Go module checksums")
makeTask("goVet", "vet", "verification", "Run go vet")
makeTask("goLint", "lint", "verification", "Run golangci-lint (pinned version)")
makeTask("goSecurityScan", "sec", "verification", "Run gosec (pinned version)")
makeTask("goVulnCheck", "vuln", "verification", "Run govulncheck (pinned version)")
makeTask("goBuild", "build", "build", "Build the CLI for the current OS and architecture")
makeTask("goBuildAll", "build-all", "build", "Build the CLI for all supported platforms")
makeTask("goTest", "test", "verification", "Run Go tests")
// Always regenerates coverage.out, so stale results can never pass.
makeTask("goTestCoverage", "cover", "verification", "Run tests with a fresh coverage profile")
makeTask("goCoverageCheck", "cover-check", "verification", "Verify per-function coverage minimums")

tasks.register<Delete>("goClean") {
  group = "build"
  description = "Clean Go CLI build artifacts"
  delete("build", "bin", "coverage.out")
}

tasks.named("assemble") {
  dependsOn("goBuild")
}

tasks.named("clean") {
  dependsOn("goClean")
}

tasks.named("check") {
  dependsOn("goTest", "goCoverageCheck", "goFormatCheck", "goModVerify", "goVet", "goLint", "goSecurityScan", "goVulnCheck")
}
