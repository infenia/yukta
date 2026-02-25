# Jagratha Agent Instructions

Welcome, Agent! This file provides essential guidelines for working on the Jagratha project.

## Core Principles

- **Reactive First**: All core logic must be non-blocking and use Reactor (`Mono`, `Flux`).
- **High Performance**: Avoid unnecessary object allocations in the execution hot path (`WorkflowOrchestrator`).
- **Safety**: Maintain thread safety through thread confinement and immutable data structures (Java Records).

## Plugin Development Guidelines

### 1. Naming Convention
- **Plugin Type**: Every plugin MUST return a unique type string from its `getType()` method.
- **Format**: Plugin types MUST be in **kebab-case** (lowercase letters, numbers, and hyphens only).
  - Correct: `api-trigger`, `sub-workflow`, `mapper`
  - Incorrect: `API_TRIGGER`, `SubWorkflow`, `Mapper`
- **Enforcement**: This is enforced via:
  - `@PluginName` annotation validation pattern.
  - `WorkflowRegistry` fail-fast check at startup.
  - `PluginTypeConsistencyTest` in the `jagratha-boot` module.
  - Checkstyle rule `pluginTypeKebabCase`.

### 2. Implementation
- Use `ProcessorPlugin`, `TriggerPlugin`, or `TerminalPlugin` interfaces.
- Provide human-readable `getDescription()` and `getUsagePattern()`.
- Define a reasonable `getDefaultTimeout()`.
- Use `SpelUtils` for any dynamic expression evaluation.

## Project Structure

- `jagratha-plugin-api`: Core interfaces for plugins.
- `jagratha-core`: Orchestration logic, validation, and registry services.
- `jagratha-boot`: Main Spring Boot application and integration tests.
- `jagratha-ui`: Frontend templates (JTE) and UI controllers.
- `plugins/`: Internal plugin implementations.

## Verification

Before submitting any changes:
1. Run `./gradlew clean build` to ensure all tests and quality checks pass.
2. Verify frontend changes by running the application and checking the UI.
3. Ensure no new Checkstyle or PMD warnings are introduced.

## Technology Stack

- **Java 21**: Primary language (target version).
- **Spring Boot**: Application framework.
- **Project Reactor**: Reactive streams library.
- **JTE**: Java Template Engine for the UI.
- **Tailwind CSS**: Utility-first CSS framework.
- **pnpm**: Node.js package manager for frontend assets.
