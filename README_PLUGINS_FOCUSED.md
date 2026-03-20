<div style="text-align: center;">
  <img src="ui/src/main/resources/static/images/favicon.svg" alt="Yukta" width="120" height="120" style="border-radius: 64px;" />
  <h1 style="margin-top: 12px;">Yukta</h1>
</div>

**Yukta** is a **reactive workflow orchestrator** that executes dynamic DAG-based workflows with millisecond-level feedback loops and enterprise-grade observability.

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Java 25+](https://img.shields.io/badge/java-25%2B-orange?style=flat-square)](https://www.oracle.com/java/)
[![Spring Boot 4.0](https://img.shields.io/badge/spring--boot-4.0-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)
[![Gradle 9.0](https://img.shields.io/badge/gradle-9.0-02303a?style=flat-square)](https://gradle.org/)
[![MCP Native](https://img.shields.io/badge/MCP-native-blueviolet?style=flat-square)](https://modelcontextprotocol.io/)
[![GraalVM](https://img.shields.io/badge/GraalVM-native%20image-red?style=flat-square)](https://www.graalvm.org/)

Define workflows as **Directed Acyclic Graphs (DAGs)** of plugins—each plugin is a reusable building block (routers, filters, process executors, transformers, etc.). Mix and match **150+ pre-built plugins** or create custom ones in minutes. Yukta executes them reactively, handles errors elegantly, and provides real-time feedback through REST APIs and MCP.

**Real-world scenarios Yukta solves:**
- 🤖 **AI agents** that need instant feedback on code quality (with Claude Code via MCP)
- 🔄 **Complex CI/CD pipelines** replacing fragile shell scripts with type-safe DAG definitions
- ✓ **Quality gates** that run tests, linters, formatters with fine-grained control
- ⚙️ **Custom automation** (deployment orchestration, data pipelines, compliance checks, webhooks)
- 🔌 **Process orchestration** (execute any OS command, chain operations, aggregate results)

---

## 🎯 The Problem Yukta Solves

**Scenario: You have a complex multi-step workflow**

```
Your workflow:
  1. Run code formatter (Spotless)
  2. Run code style checks (Checkstyle)
  3. Run unit tests (JUnit)
  4. If tests pass → generate coverage report (JaCoCo)
  5. If any step fails → send error to AI agent for auto-fix
```

**Old way**: Shell scripts with `set -e`, conditional logic scattered, hard to test, no real-time feedback
```bash
#!/bin/bash
set -e
./gradlew spotlessCheck || exit 1
./gradlew checkstyleMain || exit 1
./gradlew test || exit 1
if [ $? -eq 0 ]; then
  ./gradlew jacocoTestReport
fi
```

**Problems with this approach:**
- ❌ No visibility into which step is running (black box execution)
- ❌ Hard to conditionally branch (success path vs. error path)
- ❌ Impossible to run steps in parallel
- ❌ No integration with AI agents (feedback loop is manual)
- ❌ Errors buried in logs; hard to parse and act on

**Yukta way**: Define the workflow as a DAG with plugins
```json
{
  "name": "quality-pipeline",
  "nodes": [
    { "id": "format", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "spotlessCheck"] } },
    { "id": "lint", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "checkstyleMain"] } },
    { "id": "test", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "test"] } },
    { "id": "coverage", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "jacocoTestReport"] } },
    { "id": "notify-ai", "pluginName": "BRANCH", "config": { "selector": "#status", "mode": "EXPRESSION" } }
  ],
  "edges": [
    { "from": "format", "to": "lint" },
    { "from": "lint", "to": "test" },
    { "from": "test", "to": "coverage", "port": "success" },
    { "from": "test", "to": "notify-ai", "port": "error" }
  ]
}
```

**Benefits:**
- ✅ Real-time feedback on which step is running (observability)
- ✅ Conditional branching (success vs. error paths)
- ✅ Parallel execution where applicable (future enhancement)
- ✅ MCP integration for AI agents (instant feedback loops)
- ✅ Structured, queryable logs (JSONL format per session)

---

## 🚀 Core Concepts

### 1. **Plugins** (The Building Blocks)
Yukta comes with 150+ pre-built plugins organized by category:

#### **Process Execution**
- **PROCESS_EXECUTOR**: Execute any OS command (gradle, mvn, npm, custom scripts, shell commands)
  - Multi-OS support (Linux, macOS, Windows)
  - Streaming output for long-running processes
  - Environment variable support with SpEL
  - Timeout and error handling

#### **Routing & Branching**
- **BRANCH**: Route messages based on conditions (SELECT_KEY or SpEL expressions)
- **SPLITTER**: Split one message into many (e.g., iterate over test results)
- **AGGREGATOR**: Combine multiple messages into one (e.g., merge test reports)
- **RECIPIENT_LIST**: Send to multiple destinations in parallel
- **RESEQUENCER**: Ensure messages are processed in order (for dependent steps)

#### **Transformation & Filtering**
- **MAPPER**: Transform message payload using SpEL (e.g., extract test output)
- **CONTENT_FILTER**: Filter messages based on conditions (skip slow tests in dev mode)
- **ENRICHER**: Add metadata to messages (e.g., add timestamp, user context)
- **FILTER**: Simple pass/fail gate (e.g., reject if coverage < 80%)

#### **Flow Control**
- **LOOP_PREDICATE**: Retry a step until condition is met (retry failed test 3x)
- **LOOP_STREAM**: Iterate over a collection (run formatter on each file)
- **SUB_WORKFLOW**: Nest workflows for reusability (call "format-and-lint" sub-workflow)

#### **Triggers** (Entry points)
- **API_TRIGGER**: Accept HTTP POST to start workflow (REST API)
- **CONSTANT_SOURCE**: Use fixed input (for testing)

#### **Terminals** (Exit points)
- **CONSOLE_TERMINAL**: Log to console (debug workflows)

### 2. **DAGs** (Workflow Definition)
A workflow is defined by:
- **Nodes**: Plugins with configuration
- **Edges**: Connections between nodes (define execution order and branching)
  - Can specify output ports (e.g., success vs. error)
  - Support conditional branching

```
Example DAG topology:

  START (API_TRIGGER)
    ↓
  FORMAT (PROCESS_EXECUTOR: ./gradlew spotlessCheck)
    ↓
  LINT (PROCESS_EXECUTOR: ./gradlew checkstyleMain)
    ↓
  TEST (PROCESS_EXECUTOR: ./gradlew test)
    ├─ success → COVERAGE (PROCESS_EXECUTOR: ./gradlew jacocoTestReport)
    │              ↓
    │           REPORT (CONSOLE_TERMINAL)
    │
    └─ error → NOTIFY (BRANCH: route to error handler)
                  ↓
               CLEANUP
```

### 3. **Sessions** (Stateful Execution)
Each workflow run is a **session**:
- Unique session ID (e.g., "claude-code-session-123")
- Carries context across nodes (e.g., test results → coverage calculator)
- JSONL logs per session (queryable, machine-readable)
- Integrates with AI agents via MCP

---

## 💡 Real-World Use Cases

### **Use Case 1: AI-Assisted Code Generation with Real-Time Validation**

**Scenario**: You're using Claude Code to refactor a large codebase. You want Yukta to validate every change instantly.

**Workflow**:
1. Claude Code modifies a file
2. Yukta runs formatters (Spotless) → linters (Checkstyle) → unit tests
3. If any step fails, Yukta returns structured error to Claude Code
4. Claude Code auto-fixes and reruns validation
5. If all pass, Claude Code commits

**Why Yukta beats alternatives:**
- ✅ **MCP-native**: No polling or webhook complexity
- ✅ **Sub-millisecond feedback**: Claude Code gets error in <100ms
- ✅ **Session context**: All logs tied to a single session ID for tracing
- ✅ **Flexible DAG**: Add custom checks (e.g., "check max file size", "verify license headers")

```bash
# 1. Initialize session (Claude Code does this via hook)
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "claude-refactor-2026-03-20",
    "projectPath": "/home/user/my-project",
    "workflows": [
      {
        "name": "validate-changes",
        "nodes": [
          { "id": "format", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "spotlessApply"] } },
          { "id": "lint", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "checkstyleMain"] } },
          { "id": "test", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "test", "--fail-fast"] } }
        ],
        "edges": [
          { "from": "format", "to": "lint" },
          { "from": "lint", "to": "test" }
        ]
      }
    ]
  }'

# 2. Trigger on each file change (Claude Code does via hook)
curl -X POST http://localhost:8080/api/workflow/trigger \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "claude-refactor-2026-03-20" }'

# Response (if format fails):
# {
#   "sessionId": "claude-refactor-2026-03-20",
#   "status": "FAILED",
#   "failedNode": "format",
#   "error": "Files src/Main.java and src/Utils.java are not formatted according to Google Style Guide. Run 'spotlessApply' to fix.",
#   "timestamp": "2026-03-20T10:30:45.123Z"
# }

# Claude Code parses this, runs spotlessApply locally, retries
```

---

### **Use Case 2: Multi-Environment Deployment Pipeline**

**Scenario**: Deploy app to Dev → Staging → Prod with different validation checks at each stage.

**Workflow**:
```
Trigger (code push to repo)
  ↓
BUILD (./gradlew build)
  ├─ success → TEST (./gradlew test)
  │             ├─ success → DEPLOY_DEV (./deploy.sh dev)
  │             │             ├─ success → SMOKE_TEST_DEV (./smoke-test.sh dev)
  │             │             └─ error → ALERT_SLACK
  │             └─ error → ALERT_DEV_TEAM
  └─ error → CANCEL_DEPLOYMENT
```

**Code**:
```json
{
  "name": "deploy-pipeline",
  "nodes": [
    { "id": "build", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "build"] } },
    { "id": "test", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "test"] } },
    { "id": "deploy-dev", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./deploy.sh", "dev"] } },
    { "id": "smoke-test", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./smoke-test.sh", "dev"] } },
    { "id": "alert", "pluginName": "BRANCH", "config": { "mode": "EXPRESSION", "selector": "#status == 'FAILED'" } }
  ],
  "edges": [
    { "from": "build", "to": "test", "port": "success" },
    { "from": "test", "to": "deploy-dev", "port": "success" },
    { "from": "deploy-dev", "to": "smoke-test", "port": "success" },
    { "from": "build", "to": "alert", "port": "error" },
    { "from": "test", "to": "alert", "port": "error" }
  ]
}
```

**Why Yukta beats Jenkins/GitLab CI:**
- ✅ **No YAML hell**: DAGs are JSON, easy to generate/validate
- ✅ **Type-safe**: Compile-time plugin discovery, no "plugin not found" surprises
- ✅ **Instant feedback**: Runs on your machine (during dev), not only in CI
- ✅ **Composable**: Reuse sub-workflows (call "build-test-deploy" from multiple pipelines)

---

### **Use Case 3: Data Processing Pipeline with Conditional Logic**

**Scenario**: Process CSV files, validate, transform, and load to database based on file size.

**Workflow**:
```
INPUT (File path from user)
  ↓
VALIDATE (Check CSV format)
  ├─ size < 1GB → STREAM_TRANSFORM (SpEL mapper to transform rows)
  │                ↓
  │             BATCH_LOAD (Custom plugin to load in batches)
  │
  └─ size >= 1GB → ARCHIVE (Move to cold storage, skip load)
```

**Why Yukta beats Airflow/Prefect:**
- ✅ **Low-code, high-control**: No Python DSL learning curve; JSON DAGs for configs, plugins for logic
- ✅ **Sub-second latency**: Reactive streams, no task scheduling overhead
- ✅ **Embedded**: Run on your laptop; no server setup needed
- ✅ **Extensible**: Create custom plugins (PROCESS_EXECUTOR already handles most cases)

---

### **Use Case 4: Webhook-Triggered Automation**

**Scenario**: GitHub webhook → build project → run security scan → post results back to PR

**Workflow**:
```
GITHUB_WEBHOOK (API_TRIGGER)
  ↓
EXTRACT_PR_DETAILS (MAPPER: parse webhook payload)
  ↓
BUILD (PROCESS_EXECUTOR: ./gradlew build)
  ├─ success → SECURITY_SCAN (PROCESS_EXECUTOR: ./gradlew dependencyCheckMain)
  │             ├─ success → POST_COMMENT_PR (Custom plugin to comment on PR)
  │             └─ vulnerable → BLOCK_MERGE (Set PR status = "blocked")
  └─ error → POST_FAILURE (Post error comment)
```

**Why Yukta beats GitHub Actions:**
- ✅ **Runs locally**: Faster feedback during development
- ✅ **Reusable workflows**: One DAG for local dev, CI/CD, and local testing
- ✅ **Custom logic**: BRANCH + MAPPER let you implement complex decision trees without scripting

---

## 📋 Prerequisites

- **Java 25+** (Java 25 recommended; Gradle handles toolchain)
- **Gradle 9.0+** (included: `./gradlew` works out-of-the-box)
- **Linux/macOS/Windows** (fully tested; production-ready on all platforms)
- **Optional**: AI agent client (Claude Code, or any MCP-compatible client)

---

## 🚀 Quick Start

### 1. Clone & Start the Server

```bash
git clone https://github.com/infenia/yukta.git
cd yukta
./gradlew bootRun
```

The server starts on `http://localhost:8080` and exposes:
- **REST API**: `http://localhost:8080/api/` (POST config, POST workflow/trigger, etc.)
- **Swagger UI**: `http://localhost:8080/swagger-ui.html` (interactive API explorer)
- **MCP Server**: Native MCP integration for AI agents

### 2. Simple Quality Gate Workflow

Define a workflow that runs format → lint → test:

```bash
# Step 1: Configure session
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "quick-start",
    "projectPath": "/path/to/my-project",
    "workflows": [
      {
        "name": "quality-gate",
        "nodes": [
          { "id": "format", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "spotlessCheck"] } },
          { "id": "lint", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "checkstyleMain"] } },
          { "id": "test", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "test"] } }
        ],
        "edges": [
          { "from": "format", "to": "lint" },
          { "from": "lint", "to": "test" }
        ]
      }
    ]
  }'

# Step 2: Trigger workflow
curl -X POST http://localhost:8080/api/workflow/trigger \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "quick-start" }'

# Expected response (example of success):
# {
#   "sessionId": "quick-start",
#   "workflowName": "quality-gate",
#   "status": "SUCCESS",
#   "executionTime": "12.345s",
#   "nodeResults": [
#     { "nodeId": "format", "status": "SUCCESS", "output": "All files formatted correctly" },
#     { "nodeId": "lint", "status": "SUCCESS", "output": "No style violations" },
#     { "nodeId": "test", "status": "SUCCESS", "output": "10 tests passed" }
#   ],
#   "timestamp": "2026-03-20T10:30:45.123Z"
# }
```

### 3. Branching Workflow (Conditional Logic)

Add error handling:

```bash
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "branching-example",
    "projectPath": "/path/to/my-project",
    "workflows": [
      {
        "name": "with-error-handling",
        "nodes": [
          { "id": "test", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "test"] } },
          { "id": "coverage", "pluginName": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "jacocoTestReport"] } },
          { "id": "notify", "pluginName": "BRANCH", "config": { "mode": "EXPRESSION", "selector": "#status == '\''FAILED'\''" } }
        ],
        "edges": [
          { "from": "test", "to": "coverage", "port": "success" },
          { "from": "test", "to": "notify", "port": "error" }
        ]
      }
    ]
  }'
```

---

## 🛠️ Plugin Reference

### **PROCESS_EXECUTOR** (Most powerful plugin)
Execute any OS command with full control.

```json
{
  "pluginName": "PROCESS_EXECUTOR",
  "config": {
    "command": ["./gradlew", "build", "--info"],
    "workingDir": "/path/to/project",
    "timeout": 600,
    "env": { "JAVA_OPTS": "-Xmx2g" },
    "streamOutput": true,
    "outputTarget": "PAYLOAD"
  }
}
```

### **BRANCH** (Smart routing)
Route based on conditions.

```json
{
  "pluginName": "BRANCH",
  "config": {
    "mode": "EXPRESSION",
    "selector": "#status == 'FAILED'",
    "cases": {
      "true": { "port": "error" },
      "false": { "port": "success" }
    },
    "defaultPort": "success"
  }
}
```

### **MAPPER** (Transform data)
Transform using SpEL.

```json
{
  "pluginName": "MAPPER",
  "config": {
    "expression": "#payload.testResults[?#.passed == false]"
  }
}
```

### **Custom Plugins**
Extend Yukta with your own processors:
```java
@Component
public class MyCustomPlugin implements ProcessorPlugin {
  @Override public String getType() { return "MY_PLUGIN"; }
  @Override public Mono<Flux<Message<?>>> process(Message<?> input, Map<String, Object> config) {
    // Your logic here
  }
}
```

---

## 📚 Documentation

- **[Getting Started](docs/getting-started.md)** — Run your first workflow in 5 minutes
- **[Architecture & Design](docs/architecture.md)** — Deep dive into DAG orchestration, reactive streams, plugin system
- **[API Reference](docs/api-reference.md)** — Swagger UI and endpoint documentation
- **[Plugin Development](docs/plugin-development.md)** — Build custom plugins for your domain
- **[Development Setup](docs/development-setup.md)** — Contribute to Yukta

---

## 📊 Known Limitations & Roadmap

| Feature | Status | Notes |
|---------|--------|-------|
| **DAG Orchestration** | ✅ Stable | Full support for nodes, edges, branching, sub-workflows |
| **150+ Built-in Plugins** | ✅ Stable | Process executors, routers, transformers, filters, loops |
| **REST API** | ✅ Stable | Full HTTP interface for workflows |
| **MCP Integration** | ✅ Stable | AI agents (Claude Code) can trigger and monitor workflows |
| **Session Logging** | ✅ Stable | JSONL logs per session, fully queryable |
| **Reactive Execution** | ✅ Stable | Non-blocking, <100ms feedback loops |
| **Parallel Execution** | 🔄 Beta | Parallel nodes in DAG (next release) |
| **Custom Dashboard UI** | 📅 Planned (v0.2) | Web UI for workflow visualization |
| **Workflow Templates** | 📅 Planned (v0.2) | Pre-built templates for common workflows |
| **Database Connectors** | 📅 Planned (v0.3) | Direct DB integration plugins (PostgreSQL, MySQL, etc.) |
| **Kafka/Event Integration** | 📅 Planned (v0.3) | Trigger workflows from message brokers |

**What we prioritize:**
- ✅ Control & extensibility (not low-code simplicity)
- ✅ Performance & observability (not feature bloat)
- ✅ Developer experience (clear errors, fast feedback)

---

## 🛠️ Tech Stack

- **Java 25** (Reactive, modern language features)
- **Spring Boot 4.0.2** (WebFlux for non-blocking I/O)
- **Gradle 9.0** (Multi-module, convention plugins)
- **GraalVM** (Native image; 50MB executable)
- **Project Reactor** (Mono/Flux for reactive streams)
- **Enterprise Integration Patterns** (EIP) (Built-in routing, transformation, aggregation)

---

## 🤝 Contributing

We welcome contributions! Whether code, docs, plugins, or ideas:

- **[Contributing Guide](CONTRIBUTING.md)** — Issues, PRs, coding standards
- **[Code of Conduct](CODE_OF_CONDUCT.md)** — Our community values
- **[Good First Issues](https://github.com/infenia/yukta/labels/good%20first%20issue)** — Start here if new

**Ideas for contributions:**
- New plugins (e.g., Slack notification, PagerDuty alerting)
- Documentation (API examples, plugin tutorials)
- Testing (integration tests, plugin tests)

---

## 🛡️ Security

Found a vulnerability? Please see [Security Policy](SECURITY.md) for responsible disclosure.

**Security highlights:**
- ✅ No cloud storage (all processing is local-first)
- ✅ No code execution sandbox (yet; v0.2 will add it)
- ✅ Process isolation via OS-level controls
- ✅ Apache 2.0 license (transparent, auditable)

---

## 📄 License

This project is licensed under **Apache License 2.0** — see [LICENSE](LICENSE) for details.

**Why Apache 2.0?**
- Permissive: Use, modify, distribute freely (even commercial)
- Patent protection: Explicit patent grants
- Industry standard: Trusted by Spring, Gradle, Kubernetes

---

## 🙏 Acknowledgments & Call-to-Action

Yukta is developed and maintained by **[Infenia Private Limited](https://infenia.com)**.

- **Creator**: Arun Cherthedath Somanathan ([arun@infenia.com](mailto:arun@infenia.com))
- **Community**: Thanks to every contributor

### ⭐ If Yukta helps you, **[star the repo ❤️](https://github.com/infenia/yukta)** and consider:

- **Sharing** your experience (Twitter, Reddit, DEV.to, Hacker News)
- **Contributing** (issues, PRs, plugins, documentation)
- **Sponsoring** (buy me a coffee — link in sidebar)
- **Reporting bugs** (GitHub Issues with reproduction steps)

---

## 📞 Questions?

- **GitHub Issues**: [Ask a question](https://github.com/infenia/yukta/issues/new?labels=question)
- **Discussions**: [Yukta Community](https://github.com/infenia/yukta/discussions)
- **Email**: arun@infenia.com

---

## 🎵 Fun Fact

*Yukta* (युक्त) is a Sanskrit word meaning **"united" or "joined"**—reflecting the philosophy of orchestrating disparate tools, systems, and agents into a cohesive, harmonious workflow. 🔗

---

Happy orchestrating! 🚀
