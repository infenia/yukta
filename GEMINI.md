# Yukta (Vigilance) 🛡️

High-performance orchestrator and server designed to enforce code quality gates for AI-driven development. It acts as a bridge between AI agents (via MCP or REST), build tools, and AI feedback models.

## 🏗️ Project Overview

Yukta facilitates autonomous code validation by hosting a **Model Context Protocol (MCP)** server. It triggers quality checks (Spotless, Checkstyle, PMD), processes outputs into structured JSONL, and optionally invokes AI models for intelligent feedback on failures.

- **Primary Mission**: Ensure AI-generated code meets strict quality standards before merging.
- **Key Architecture**: Plugin-based, build-tool agnostic, and session-aware.
- **Reactive Engine**: Built on Spring Boot WebFlux for non-blocking operations.

## 🛠️ Tech Stack

- **Runtime**: Java 25 (targets Java 25, uses Java 21 toolchain for compatibility).
- **Framework**: Spring Boot 4.0.2 (WebFlux, Spring AI for MCP).
- **Build System**: Gradle 9.0 (Multi-module).
- **UI**: JTE (Java Templating Engine), Tailwind CSS 4.
- **Integration**: Model Context Protocol (MCP) native.

## 🚦 Building and Running

### Essential Commands
- **Start Server**: `./gradlew bootRun` (Runs the Spring Boot application).
- **Run Quality Checks**: `./gradlew check` (Runs tests, Checkstyle, PMD, SpotBugs, and JaCoCo).
- **Format Code**: `./gradlew spotlessApply` (Automatically fixes formatting and license headers).
- **Clean Build**: `./gradlew clean build`

### UI Development
The UI module (`yukta-ui`) uses Tailwind CSS 4. JTE templates are located in `src/main/jte`.

## 📏 Development Conventions

### Coding Style
- **Standard**: [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
- **Indentation**: 2 spaces.
- **Line Length**: 100 characters.
- **Formatting**: Enforced via **Spotless**. Always run `./gradlew spotlessApply` before committing.
- **Documentation**: All public/protected methods require Javadoc.

### Programming Patterns
- **Reactive Streams**: Use Project Reactor (`Mono`, `Flux`) for all service and controller logic.
- **Immutability**: Prefer immutable models and records (Java 16+ features).
- **Lombok**: Extensively used for boilerplate reduction (`@Getter`, `@RequiredArgsConstructor`, etc.).
- **Validation**: Jakarta Bean Validation (`@Valid`, `@Validated`) is used at the service and controller layers.

### Git & Workflow
- **Commit Messages**: Follow [Conventional Commits](https://www.conventionalcommits.org/) (e.g., `feat:`, `fix:`, `chore:`, `docs:`).
- **License Headers**: Every Java file MUST include the Apache License 2.0 header (managed by Spotless).
- **Quality Gates**: Every PR must pass `checkstyle`, `pmd`, and `spotbugs` without warnings.

## 📁 Key File Locations

- **Core Logic**: `yukta-core/src/main/java/com/infenia/yukta/`
- **MCP Tools**: `yukta-boot/src/main/java/com/infenia/yukta/mcp/`
- **Plugin API**: `yukta-plugin-api/`
- **UI Templates**: `yukta-ui/src/main/jte/`
- **Checkstyle Config**: `config/checkstyle/checkstyle.xml`
- **Session Logs**: Configured via `application.yaml` (default uses session-specific JSONL files).
