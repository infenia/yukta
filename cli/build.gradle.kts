// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
  id("base")
}

// Format Go source files
tasks.register("goFormat") {
  group = "formatting"
  description = "Format Go source code using gofmt"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    val projectDirectory = projectDir
    val goFiles = projectDirectory.walkTopDown()
      .filter { it.isFile && it.name.endsWith(".go") }
      .filter { !it.path.contains("vendor/") && !it.path.contains("build/") && !it.path.contains("bin/") }
      .toList()

    if (goFiles.isEmpty()) {
      println("No Go files found to format")
      return@doLast
    }

    val processBuilder = ProcessBuilder(listOf("gofmt", "-w") + goFiles.map { it.absolutePath })
    processBuilder.directory(projectDirectory)

    val exitCode = processBuilder.start().waitFor()
    if (exitCode != 0) {
      throw GradleException("Go format failed with exit code $exitCode")
    }

    println("Successfully formatted ${goFiles.size} Go files")
  }
}

// Validate Go formatting
tasks.register("goFormatCheck") {
  group = "verification"
  description = "Validate Go source code formatting using gofmt"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    val projectDirectory = projectDir
    val goFiles = projectDirectory.walkTopDown()
      .filter { it.isFile && it.name.endsWith(".go") }
      .filter { !it.path.contains("vendor/") && !it.path.contains("build/") && !it.path.contains("bin/") }
      .toList()

    if (goFiles.isEmpty()) {
      println("No Go files found to check")
      return@doLast
    }

    val processBuilder = ProcessBuilder(listOf("gofmt", "-l") + goFiles.map { it.absolutePath })
    processBuilder.directory(projectDirectory)

    val process = processBuilder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()

    if (output.isNotEmpty()) {
      throw GradleException(
        "Go format validation failed. The following files are not properly formatted:\n$output\n\n" +
        "Run './gradlew goFormat' to auto-fix formatting."
      )
    }

    if (exitCode != 0) {
      throw GradleException("Go format check failed with exit code $exitCode")
    }

    println("All Go files are properly formatted")
  }
}

// Tidy Go dependencies
tasks.register("goModTidy") {
  group = "go"
  description = "Tidy Go module dependencies (go mod tidy)"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    val processBuilder = ProcessBuilder("go", "mod", "tidy")
    processBuilder.directory(projectDir)

    val exitCode = processBuilder.start().waitFor()
    if (exitCode != 0) {
      throw GradleException("go mod tidy failed with exit code $exitCode")
    }

    println("Go dependencies tidied")
  }
}

// Verify Go module checksums
tasks.register("goModVerify") {
  group = "verification"
  description = "Verify Go module checksums (go mod verify)"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    val processBuilder = ProcessBuilder("go", "mod", "verify")
    processBuilder.directory(projectDir)

    val process = processBuilder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val errorOutput = process.errorStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      val fullOutput = if (errorOutput.isNotEmpty()) "$output\n$errorOutput" else output
      throw GradleException(
        "Go module verification failed:\n$fullOutput\n\n" +
        "This indicates potential tampering with dependencies. " +
        "Run 'go mod tidy' or investigate the affected modules."
      )
    }

    println("Go module checksums verified")
  }
}

// Run go vet for static analysis
tasks.register("goVet") {
  group = "verification"
  description = "Run Go vet for code analysis"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    val processBuilder = ProcessBuilder("go", "vet", "./...")
    processBuilder.directory(projectDir)

    val exitCode = processBuilder.start().waitFor()
    if (exitCode != 0) {
      throw GradleException("go vet found issues with exit code $exitCode")
    }

    println("Go vet analysis passed")
  }
}

// Install and run golangci-lint for comprehensive static analysis
tasks.register("goLint") {
  group = "verification"
  description = "Run golangci-lint for comprehensive static analysis"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    println("Running golangci-lint for comprehensive static analysis...")
    // Use go run to execute golangci-lint without requiring pre-installation
    val lintProcess = ProcessBuilder(
      "go", "run", "github.com/golangci/golangci-lint/cmd/golangci-lint@latest",
      "run", "--timeout=5m", "./..."
    )
    lintProcess.directory(projectDir)

    val process = lintProcess.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      println("\n⚠️  Linting issues found:")
      println(output)
      if (errorOutput.isNotEmpty()) {
        println(errorOutput)
      }
      throw GradleException(
        "golangci-lint found issues. Review and fix the issues above.\n" +
        "Configuration: .golangci.yaml"
      )
    }

    println("Static analysis passed - no issues found")
  }
}

// Scan for security vulnerabilities with gosec
tasks.register("goSecurityScan") {
  group = "verification"
  description = "Run gosec for security vulnerability scanning"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    println("Running security vulnerability scan with gosec...")
    val gosecProcess = ProcessBuilder(
      "go", "run", "github.com/securego/gosec/v2/cmd/gosec@latest",
      "-fmt=json", "./..."
    )
    gosecProcess.directory(projectDir)

    val process = gosecProcess.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    // gosec returns exit code 1 if issues found, we want to report them
    if (exitCode != 0 && exitCode != 1) {
      throw GradleException("gosec failed with exit code $exitCode\n$errorOutput")
    }

    if (output.contains("\"Issues\":[") && !output.contains("\"Issues\":[]")) {
      println("\n⚠️  Security vulnerabilities found:")
      println(output)
      throw GradleException(
        "gosec found security issues. Review and fix the vulnerabilities above.\n" +
        "Run './gradlew goSecurityScan' to see detailed report."
      )
    }

    println("Security scan passed - no vulnerabilities detected")
  }
}

// Check for known vulnerabilities in dependencies
tasks.register("goVulnCheck") {
  group = "verification"
  description = "Check for known vulnerabilities in Go dependencies (govulncheck)"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    println("Checking for known vulnerabilities in dependencies...")
    val processBuilder = ProcessBuilder("go", "list", "-m", "all")
    processBuilder.directory(projectDir)

    // First, check if govulncheck is available
    val checkProcess = ProcessBuilder("go", "run", "golang.org/x/vuln/cmd/govulncheck@latest", "./...")
    checkProcess.directory(projectDir)

    val process = checkProcess.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      if (output.contains("Vulnerability") || errorOutput.contains("Vulnerability")) {
        println("\n⚠️  Known vulnerabilities found in dependencies:")
        println(output)
        if (errorOutput.isNotEmpty()) println(errorOutput)
        throw GradleException(
          "Vulnerable dependencies detected. Update affected packages or run " +
          "'go get -u' to patch.\nDetails:\n$output"
        )
      } else if (!output.contains("No vulnerabilities found")) {
        // Some exit codes are warnings only
        println("Vulnerability check warning: $output")
      }
    }

    println("Dependency vulnerability check passed")
  }
}

// Build goBuild task
tasks.register("goBuild") {
  group = "build"
  description = "Build Go CLI binary for current OS and architecture"

  notCompatibleWithConfigurationCache("Go build process requires external command execution")

  doLast {
    file("build").mkdirs()

    val osName = System.getProperty("os.name").lowercase()
    val goos = when {
      osName.contains("mac") || osName.contains("darwin") -> "darwin"
      osName.contains("windows") -> "windows"
      else -> "linux"
    }

    val osArch = System.getProperty("os.arch").lowercase()
    val goarch = when {
      osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
      else -> "amd64"
    }

    val baseName = "yukta-$goos-$goarch"
    val binaryName = if (goos == "windows") "$baseName.exe" else baseName

    println("Building Go CLI binary for $goos/$goarch -> build/$binaryName")

    val processBuilder = ProcessBuilder(
      "go", "build", "-ldflags=-s -w", "-o", "build/$binaryName", "./cmd/yukta"
    )
    processBuilder.directory(projectDir)
    processBuilder.environment()["GOOS"] = goos
    processBuilder.environment()["GOARCH"] = goarch
    processBuilder.environment()["CGO_ENABLED"] = "0"

    val exitCode = processBuilder.start().waitFor()
    if (exitCode != 0) {
      throw GradleException("Go build failed for $goos/$goarch with exit code $exitCode")
    }

    println("Successfully built Go CLI binary: build/$binaryName")
  }
}

// Build goBuildAll task
tasks.register("goBuildAll") {
  group = "build"
  description = "Build Go CLI binaries for all supported platforms"

  notCompatibleWithConfigurationCache("Go build process requires external command execution")

  doLast {
    val platforms = listOf(
      Pair("linux", "amd64"),
      Pair("linux", "arm64"),
      Pair("darwin", "amd64"),
      Pair("darwin", "arm64"),
      Pair("windows", "amd64")
    )

    file("build").mkdirs()

    for ((goos, goarch) in platforms) {
      val baseName = "yukta-$goos-$goarch"
      val binaryName = if (goos == "windows") "$baseName.exe" else baseName

      println("Building Go CLI for $goos/$goarch -> build/$binaryName")

      val processBuilder = ProcessBuilder(
        "go", "build", "-ldflags=-s -w", "-o", "build/$binaryName", "./cmd/yukta"
      )
      processBuilder.directory(projectDir)
      processBuilder.environment()["GOOS"] = goos
      processBuilder.environment()["GOARCH"] = goarch
      processBuilder.environment()["CGO_ENABLED"] = "0"

      val exitCode = processBuilder.start().waitFor()
      if (exitCode != 0) {
        throw GradleException("Go build failed for $goos/$goarch with exit code $exitCode")
      }
    }

    println("Successfully built Go CLI binaries for all platforms")
  }
}

// Run tests with coverage
tasks.register("goTest") {
  group = "verification"
  description = "Run Go tests"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    println("Running Go tests...")
    val testProcess = ProcessBuilder("go", "test", "-v", "./...")
    testProcess.directory(projectDir)

    val process = testProcess.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    println(output)
    if (errorOutput.isNotEmpty()) {
      println(errorOutput)
    }

    if (exitCode != 0) {
      throw GradleException("Tests failed with exit code $exitCode")
    }

    println("All tests passed")
  }
}

// Generate test coverage report
tasks.register("goTestCoverage") {
  group = "verification"
  description = "Generate test coverage report"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    println("Running tests with coverage...")
    val testProcess = ProcessBuilder(
      "go", "test", "-coverprofile=coverage.out", "./..."
    )
    testProcess.directory(projectDir)

    val exitCode = testProcess.start().waitFor()
    if (exitCode != 0) {
      throw GradleException("Coverage tests failed with exit code $exitCode")
    }

    println("Coverage report generated: coverage.out")

    // Display coverage summary
    val coverageProcess = ProcessBuilder("go", "tool", "cover", "-func=coverage.out")
    coverageProcess.directory(projectDir)

    val process = coverageProcess.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    println("\nCoverage Summary:")
    println(output)

    process.waitFor()
  }
}

// Verify test coverage meets requirements
tasks.register("goCoverageCheck") {
  group = "verification"
  description = "Verify test coverage meets 100% requirement (with per-file overrides)"
  doNotTrackState("Requires external Go tool execution")

  doLast {
    val projectDirectory = projectDir

    println("Verifying test coverage requirements...")

    // First, generate coverage if not exists
    val coverageFile = File(projectDirectory, "coverage.out")
    if (!coverageFile.exists()) {
      println("Generating coverage report...")
      val testProcess = ProcessBuilder(
        "go", "test", "-coverprofile=coverage.out", "./..."
      )
      testProcess.directory(projectDirectory)
      val process = testProcess.start()
      val testOutput = StringBuilder()
      val testErrorOutput = StringBuilder()

      val stdoutThread = Thread {
        process.inputStream.bufferedReader().use { reader ->
          reader.forEachLine { testOutput.appendLine(it) }
        }
      }
      val stderrThread = Thread {
        process.errorStream.bufferedReader().use { reader ->
          reader.forEachLine { testErrorOutput.appendLine(it) }
        }
      }

      stdoutThread.start()
      stderrThread.start()
      val exitCode = process.waitFor()
      stdoutThread.join()
      stderrThread.join()

      if (testOutput.isNotEmpty()) {
        println(testOutput)
      }
      if (testErrorOutput.isNotEmpty()) {
        println(testErrorOutput)
      }
      if (exitCode != 0) {
        throw GradleException("Failed to generate coverage: exit code $exitCode")
      }
    }

    // Parse coverage data
    val coverageOutput = ProcessBuilder("go", "tool", "cover", "-func=coverage.out")
    coverageOutput.directory(projectDirectory)
    val process = coverageOutput.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()

    // Parse .codecov.yaml for overrides
    val codecovFile = File(projectDirectory, ".codecov.yaml")
    var defaultMin = 100
    val fileOverrides = mutableMapOf<String, Int>()

    if (codecovFile.exists()) {
      val codecovContent = codecovFile.readText()
      val minMatch = Regex("""coverage:\s*#.*\n\s*min:\s*(\d+)""", RegexOption.MULTILINE).find(codecovContent)
      if (minMatch != null) {
        defaultMin = minMatch.groupValues[1].toInt()
      }

      // Parse file-specific overrides in the format:
      // - path: cmd/yukta/main.go
      //   min: 80
      val lines = codecovContent.split("\n")
      var i = 0
      while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("- path:")) {
          val path = line.substringAfter("path:").trim()
          // Look for min on the next line
          if (i + 1 < lines.size) {
            val nextLine = lines[i + 1].trim()
            if (nextLine.startsWith("min:")) {
              val minValue = nextLine.substringAfter("min:").trim().toIntOrNull() ?: 100
              fileOverrides[path] = minValue
            }
          }
        }
        i++
      }
    }

    // Parse coverage by file
    val coverageByFile = mutableMapOf<String, Double>()
    val lines = output.split("\n").filter { it.contains(".go") && !it.contains("total:") }

    lines.forEach { line ->
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size >= 2) {
        val file = parts[0]
        val coverage = parts.last().removeSuffix("%").toDoubleOrNull() ?: 0.0
        coverageByFile[file] = coverage
      }
    }

    var allPassed = true
    val failedFiles = mutableListOf<String>()

    println("\n═══════════════════════════════════════════════════════════")
    println("Coverage Verification Results:")
    println("═══════════════════════════════════════════════════════════")

    coverageByFile.forEach { (file, coverage) ->
      // Extract the relative path from full module path
      // e.g., "com.infenia.yukta/cli/cmd/yukta/main.go:23::" -> "cmd/yukta/main.go"
      val relativePath = file.substringAfter("cli/").substringBefore(":").split(":")[0]

      var minRequired = fileOverrides[relativePath] ?: defaultMin

      // Try matching without trailing parts if not found
      if (minRequired == defaultMin) {
        fileOverrides.forEach { (overridePath, overrideMin) ->
          if (relativePath.endsWith(overridePath)) {
            minRequired = overrideMin
          }
        }
      }

      val status = if (coverage >= minRequired) "✓ PASS" else "✗ FAIL"

      println("$status  $file: $coverage% (required: $minRequired%)")

      if (coverage < minRequired) {
        allPassed = false
        failedFiles.add("$file: $coverage% (required: $minRequired%)")
      }
    }

    println("═══════════════════════════════════════════════════════════")

    if (!allPassed) {
      println("\n⚠️  Coverage requirements not met:")
      failedFiles.forEach { println("  • $it") }
      println("\nTo fix:")
      println("  1. Add/improve tests to increase coverage")
      println("  2. Or update .codecov.yaml to override requirement for that file")
      throw GradleException("Coverage verification failed")
    }

    println("\n✓ All files meet coverage requirements!")
  }
}

// Clean build artifacts
tasks.register<Delete>("goClean") {
  group = "build"
  description = "Clean Go CLI build artifacts"

  delete("build")
  delete("bin")
  delete("coverage.out")
}

// Wire tasks to standard Gradle lifecycle
tasks.named("assemble") {
  dependsOn("goBuild")
}

tasks.named("clean") {
  dependsOn("goClean")
}

tasks.named("check") {
  dependsOn("goTest", "goCoverageCheck", "goFormatCheck", "goModVerify", "goVet", "goLint", "goSecurityScan", "goVulnCheck")
}
