# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Jagratha** (Sanskrit for *Vigilance*) is a high-performance server that enforces code quality gates for AI-driven development. It hosts a Model Context Protocol (MCP) server, integrates with AI agents, and provides a reactive DAG-based workflow engine for orchestrating quality checks and custom plugins.

**Key Architecture**: The project is a multi-module Gradle build consisting of:
- **jagratha-plugin-api**: Plugin abstraction interfaces (Trigger, Processor, Terminal plugins)
- **jagratha-core**: Core service layer (orchestration, workflow registry, session management)
- **jagratha-ui**: JTE (Java Templating Engine) UI module with Tailwind CSS
- **jagratha-boot**: Spring Boot application entry point and MCP server
- **plugins/build-tools/gradle**: Default Gradle plugin for quality checks
- **build-logic**: Gradle build conventions (Java, Quality gates, JaCoCo)

## Tech Stack

- **Java 21** (source & target compatibility; uses Java 25+ compiler for toolchain)
- **Spring Boot 4.0.2** (WebFlux for reactive non-blocking operations)
- **Gradle 9.0** (multi-module with convention plugins)
- **GraalVM** (native image support)
- **JTE** (Java Templating Engine) + **Tailwind CSS 4**
- **Project Reactor** (Mono, Flux for reactive streams)

## Build & Development Commands

### Essential Commands
```bash
# Start the application
./gradlew bootRun

# Run all quality checks (tests, Checkstyle, PMD, SpotBugs, JaCoCo)
./gradlew check

# Format code (Spotless - must do before commit)
./gradlew spotlessApply

# Clean build
./gradlew clean build

# Run tests for a specific module
./gradlew :jagratha-core:test

# Run a single test class
./gradlew :jagratha-core:test --tests com.infenia.jagratha.service.WorkflowOrchestratorTest
```

### Development Tasks
```bash
# Access Swagger UI (after bootRun)
# http://localhost:8080/swagger-ui.html

# UI development: pnpm is used for Node dependencies (Tailwind CSS)
# JTE templates are precompiled automatically during build

# Build native image (GraalVM)
./gradlew nativeCompile
```

## Code Architecture & Patterns

### Reactive Streams (Core Pattern)
All service and controller logic uses **Project Reactor** (`Mono`, `Flux`). Key principles:
- Controllers return `Mono<ResponseEntity<T>>` or `Mono<T>`
- Services use `Mono` for single operations, `Flux` for streams
- Avoid blocking operations; use `.block()` only in tests

**Example**:
```java
public Mono<TaskResponse> executeTask(WorkflowTriggerRequest request) {
    return validateRequest(request)
        .flatMap(this::executeWorkflow)
        .doOnError(error -> log.error("Task execution failed", error));
}
```

### Plugin System (Extensible DAG-Based)
Located in **jagratha-plugin-api**, plugins are classified into three categories:
1. **TriggerPlugin**: Initiates workflows (e.g., API endpoint)
2. **ProcessorPlugin**: Transforms or validates data (e.g., quality checks)
3. **TerminalPlugin**: Finalizes workflows (e.g., logging, feedback)

Plugins are registered in `WorkflowRegistry` and orchestrated by `WorkflowOrchestrator` which executes a **Directed Acyclic Graph (DAG)** of nodes and edges defined in `WorkflowDefinition`.

**Key Files**:
- `jagratha-core/src/main/java/com/infenia/jagratha/service/WorkflowOrchestrator.java` - DAG orchestration logic
- `jagratha-core/src/main/java/com/infenia/jagratha/service/WorkflowRegistry.java` - Plugin registration
- `jagratha-plugin-api/src/main/java/com/infenia/jagratha/plugin/` - Plugin interfaces

### Immutability & Records
Use **Java records** for data models (preferred) or **Lombok** (`@RequiredArgsConstructor`, `@Getter`) for immutable objects. Example:
```java
public record WorkflowDefinition(
    @NotEmpty @Valid List<Node> nodes,
    @NotNull @Valid List<Edge> edges) {}
```

### Validation
- Use **Jakarta Bean Validation** (`@Valid`, `@Validated`, custom validators like `@SessionId`, `@ProjectPath`)
- Custom validators are in `jagratha-core/src/main/java/com/infenia/jagratha/validation/`
- Validation errors are handled globally by `GlobalExceptionHandler`

### Session Management
- `SessionService` manages session state and logging
- Session logs are JSONL-formatted and session-aware (per-request isolation)
- Configuration via `application.yaml` under spring.ai.mcp.server

### MapStruct Mapping
Used for DTO mapping: `AppConfigMapper` converts between REST requests/responses and domain models. Processor: `annotationProcessor libs.mapstruct.processor` + `annotationProcessor libs.lombok.mapstruct.binding`.

## Code Style & Quality Gates

### Formatting & License Headers
- **Spotless** enforces **Google Java Style Guide** (2-space indentation, 100-char line limit)
- Every Java file MUST have an Apache License 2.0 header (managed by Spotless)
- Always run `./gradlew spotlessApply` before committing

### Static Analysis
- **Checkstyle**: Style violations (config: `config/checkstyle/checkstyle.xml`)
- **PMD**: Code quality rules (config: `config/pmd/ruleset.xml`)
- **SpotBugs**: Bug detection (enabled via quality-conventions)
- Quality gates are applied ONLY to main source code, not tests or AOT

### Test Framework
- **JUnit 5** with **Reactor Test** (`reactor.test.publisher.TestPublisher`, `StepVerifier`)
- Use `StepVerifier` for testing reactive streams:
  ```java
  StepVerifier.create(mono)
      .expectNext(expectedValue)
      .verifyComplete();
  ```

### JaCoCo Code Coverage
- Configured per module with minimum thresholds
- Run `./gradlew jacocoTestReport` to generate coverage reports
- Coverage requirements prevent low-coverage code from merging

## Build Conventions (build-logic)

The `build-logic` directory defines three reusable convention plugins:
1. **java-conventions**: Java toolchain (21), Lombok, repositories, JUnit 5
2. **quality-conventions**: Spotless, Checkstyle, PMD, SpotBugs, license headers
3. **jacoco-conventions**: Code coverage tracking

All modules apply these plugins via `plugins { id 'com.infenia.jagratha.xxx-conventions' }`.

## UI Development (jagratha-ui)

- **Templates**: JTE files in `jagratha-ui/src/main/jte/` (pre-compiled to bytecode)
- **Styling**: Tailwind CSS 4 compiled during build
- **CSS Input**: `jagratha-ui/src/main/resources/static/css/input.css` (processed by `pnpm exec tailwindcss`)
- **Node Dependencies**: Managed via `pnpm` (see build.gradle tasks: `pnpmInstall`, `tailwind`)

## Git Workflow

### Commit Conventions
Use **Conventional Commits**:
- `feat: description` - new features
- `fix: description` - bug fixes
- `docs: description` - documentation changes
- `refactor: description` - code refactoring (no behavior change)
- `test: description` - test additions
- `chore: description` - build/tooling updates
- `style: description` - formatting only (no code logic change)

Example: `feat: add reactive DAG workflow engine for plugin orchestration`

### Pull Request Workflow
1. Create branch from `main`
2. Run `./gradlew spotlessApply` to format code
3. Run `./gradlew check` to verify all quality gates pass
4. Submit PR with descriptive title and issue references
5. Ensure all tests pass before merge

## Key File Locations

- **Core Services**: `jagratha-core/src/main/java/com/infenia/jagratha/service/`
- **MCP Tools**: `jagratha-core/src/main/java/com/infenia/jagratha/mcp/` (MCP native integration)
- **Controllers**: `jagratha-core/src/main/java/com/infenia/jagratha/controller/`
- **Plugin API**: `jagratha-plugin-api/src/main/java/com/infenia/jagratha/plugin/`
- **Configuration**: `jagratha-core/src/main/java/com/infenia/jagratha/config/`
- **Validation**: `jagratha-core/src/main/java/com/infenia/jagratha/validation/`
- **Quality Config**: `config/checkstyle/`, `config/pmd/`, `config/license/`
- **Application Config**: `jagratha-boot/src/main/resources/application.yaml`

## Testing Strategy

- Unit tests use JUnit 5 + Mockito (for reactive tests: `reactor.test`)
- Test files follow naming: `*Test.java` (in `src/test/java`)
- Quality gates (Checkstyle, PMD, SpotBugs) are disabled for test code
- Coverage thresholds vary per module (e.g., UI has 5% minimum via `jacocoMinimumCoverage`)

## Common Development Tasks

**Add a new service**: Create in `jagratha-core/src/main/java/com/infenia/jagratha/service/`, use `@Service`, return `Mono`/`Flux`, add tests.

**Add a new plugin**: Extend `WorkflowPlugin`/`ProcessorPlugin`/`TriggerPlugin` in plugin-api, register in `WorkflowRegistry` during bean initialization.

**Modify workflow DAG**: Update `WorkflowDefinition` records and `WorkflowOrchestrator` execution logic.

**Add REST endpoint**: Create controller in `AppController` or new controller, return `Mono<ResponseEntity<T>>`, use validation annotations.

**Update configuration**: Edit `application.yaml` or add Spring `@ConfigurationProperties` class with validation.

## Troubleshooting

- **Spotless fails**: Run `./gradlew spotlessApply` to auto-fix formatting
- **Tests fail**: Ensure Reactor streams are properly tested with `StepVerifier`
- **Quality checks fail**: Check Checkstyle (`config/checkstyle/checkstyle.xml`) and PMD (`config/pmd/ruleset.xml`) rules
- **Build cache issues**: Run `./gradlew clean build` to clear cached outputs
- **Native image build fails**: Check GraalVM compatibility in `jagratha-boot/build.gradle` graalvmNative config
