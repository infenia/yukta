<div style="text-align: center;">
  <img src="ui/src/main/resources/static/images/favicon.svg" alt="Yukta" width="120" height="120" style="border-radius: 64px; padding: 4px; background-color: #fff;" />
  <h1 style="margin-top: 12px;">Yukta</h1>
  <p><strong>Orchestrate multi-step workflows with confidence.</strong> Define once, execute anywhere, integrate with AI.</p>
</div>

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Java 25+](https://img.shields.io/badge/java-25%2B-orange?style=flat-square)](https://www.oracle.com/java/)
[![Spring Boot 4.0](https://img.shields.io/badge/spring--boot-4.0-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)
[![Gradle 9.0](https://img.shields.io/badge/gradle-9.0-02303a?style=flat-square)](https://gradle.org/)
[![MCP Native](https://img.shields.io/badge/MCP-native-blueviolet?style=flat-square)](https://modelcontextprotocol.io/)
[![GraalVM](https://img.shields.io/badge/GraalVM-native%20image-red?style=flat-square)](https://www.graalvm.org/)

---

## The Problem

You juggle workflows every day:
- **Developers**: "Run tests after formatting, but skip if linting fails"
- **DevOps Engineers**: "Build → Test → Deploy, with rollback on error"
- **Data Pipelines**: "Validate data → Transform → Load, retrying on timeout"
- **AI Teams**: "Run check, get feedback, auto-fix, rerun"

Today, you solve this with **shell scripts** 🐚. Tomorrow, you're debugging them at 3am.

```bash
#!/bin/bash
./gradlew format || exit 1
./gradlew lint || { echo "lint failed"; slack-notify; exit 1; }
./gradlew test || deploy-rollback
```

**The Problem**: Scripts are hard-coded, fragile, error-prone, and don't integrate with modern AI tools.

---

## The Solution: Yukta

**Yukta** is a **workflow orchestrator** that handles the complexity so you don't have to.

### Think of it like this:

| Approach | Problem | Yukta Solution |
|----------|---------|---|
| 🐚 Shell Scripts | Hard to read, error-prone, no reusability | 📋 JSON DAG (visual, reusable, validated) |
| 🔗 Manual Orchestration | "Did that step run? What failed?" | 🎯 Real-time status via REST API + SSE |
| ⚙️ Custom Code | Takes weeks to build, hard to maintain | 🔌 16 built-in plugins, no code needed |
| 🤖 AI Integration | Webhooks, polling, manual retries | 🧠 Native MCP support, instant feedback |
| 🚨 Error Handling | `if [ $? -eq 0 ]` (good luck) | 🌳 Branching, retries, conditional routing |

---

## Why Yukta Matters to You Personally

👨‍💼 **Project Manager / Tech Lead**
> "Stop approving hotfixes for broken workflows. Define once, get visibility, sleep at night."
- Single source of truth for all workflows (no tribal knowledge)
- Real-time dashboard (who's running what, what's failing, why)
- Audit trail (compliant, traceable, blame-free)

👨‍💻 **Developer**
> "Write JSON, not bash. Get instant feedback from AI while you code."
- No shell script debugging at 3am
- Workflows run locally, in CI/CD, in production—same definition
- AI auto-fixes your code when tests fail (via MCP integration)
- Test-driven workflow development (StepVerifier for Reactor streams)

👷 **DevOps Engineer**
> "Deploy once, run everywhere. 50MB executable, zero operational overhead."
- Native executable (no JVM, no container bloat)
- REST API + MCP for any orchestration tool
- Built-in observability (no external monitoring needed; events batched 100@50ms)
- Observable: heartbeats every 10s, structured JSONL logs, live DAG visualization

🏗️ **Architect / CTO**
> "Modular, testable, extensible. Write one plugin, use everywhere."
- Clean 3-layer architecture (Plugin → Orchestration → Transport)
- 16 built-in plugins, custom plugins in minutes
- Battle-tested reactive patterns (Project Reactor, virtual threads, <100ms loops)
- Future-proof: Any AI agent via MCP, local-first design, no vendor lock-in

---

## How It Works (The Pitch)

### For **Developers**:
```json
{
  "sessionId": "my-project",
  "workflows": {
    "quality-check": {
      "description": "Format → Lint → Test",
      "nodes": [
        { "nodeId": "format", "type": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "spotlessCheck"] } },
        { "nodeId": "lint", "type": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "checkstyleMain"] } },
        { "nodeId": "test", "type": "PROCESS_EXECUTOR", "config": { "command": ["./gradlew", "test"] } }
      ],
      "edges": [
        { "source": "format", "target": "lint" },
        { "source": "lint", "target": "test" }
      ]
    }
  }
}
```

**That's it.** No scripts. No `if` statements. No manual error handling. Just describe the flow, and Yukta runs it.

### For **DevOps Engineers**:
```bash
# Trigger the workflow
curl -X POST http://localhost:8080/api/workflow/trigger \
  -d '{ "sessionId": "my-project", "workflowId": "quality-check", "payload": {} }'

# Returns instantly with execution ID
{ "executionId": "550e8400-e29b-41d4-a716-446655440000" }

# Stream live status (Server-Sent Events)
curl http://localhost:8080/api/workflow/my-project/status/.../stream

# Real-time output:
# { "status": "RUNNING", "tasks": [{ "nodeId": "format", "status": "SUCCESS" }, ...] }
# { "status": "RUNNING", "tasks": [{ "nodeId": "lint", "status": "SUCCESS" }, ...] }
# { "status": "SUCCESS", "tasks": [...] }
```

**No polling. No webhooks. Just REST + SSE.**

### For **AI Teams** (Any MCP-Compatible Agent):
```python
# MCP Integration with any AI agent
# Supports: Claude Code, Claude API, and any agent implementing MCP

# Connection:
# AI Agent → MCP Protocol → Yukta (/sse endpoint)
# Capabilities: 13 MCP tools (create_session, trigger_workflow, get_status, etc.)

# Example with Claude Code (SessionStart hook):
#   SessionStart → init_session.py → POST /api/config (via MCP)
#   Stop → trigger_workflow.py → POST /api/workflow/trigger (via MCP)

# Example with Claude API (custom integration):
#   Agent connects to http://localhost:8080/sse (MCP STATELESS protocol)
#   Agent calls create_session tool
#   Agent calls trigger_workflow tool
#   Agent streams status in real-time (no polling)

# AI agent gets instant feedback:
# ✅ Tests passed → commit changes
# ❌ Tests failed → AI reads error, auto-fixes, retries
```

**The AI doesn't wait. It doesn't poll. It gets instant feedback via MCP and self-corrects. Works with any AI that supports MCP.**

---

## Three Layers (Architects Will Love This)

```
┌─────────────────────────────────────┐
│ 3. Transport Layer                  │
│ REST API (16 endpoints) + MCP       │
│ for humans and AI agents            │
└──────────────┬──────────────────────┘
               ↓
┌─────────────────────────────────────┐
│ 2. Orchestration Layer              │
│ DAG validator, compiler, executor   │
│ Reactive (Mono/Flux), <100ms loops  │
└──────────────┬──────────────────────┘
               ↓
┌─────────────────────────────────────┐
│ 1. Plugin Layer                     │
│ 16 composable, extensible plugins   │
│ (TriggerPlugin, ProcessorPlugin,    │
│  TerminalPlugin)                    │
└─────────────────────────────────────┘
```

**Each layer is independent.** Swap plugins. Change execution. Integrate anywhere.

---

## What Makes Yukta Different

### 🎯 **Define Once, Execute Anywhere**
- Local dev machine, CI/CD pipeline, AI agent callback
- Same JSON DAG, different execution contexts

### ⚡ **Sub-100ms Feedback**
- Reactive non-blocking (Project Reactor)
- Virtual threads for I/O
- Real-time SSE streaming

### 🧠 **Built for AI**
- MCP-native integration (Claude Code, Claude API, others)
- Instant feedback (no polling)
- Self-correcting workflows

### 🔌 **16 Plugins, Infinite Combinations**
- PROCESS_EXECUTOR (run any OS command)
- BRANCH (conditional routing via SpEL)
- MAPPER, FILTER, SPLITTER, AGGREGATOR, LOOP, SUB_WORKFLOW, and more
- Write custom plugins in minutes

### 🛡️ **Enterprise-Ready**
- Input validation at all boundaries (blocks injection, path traversal)
- Session isolation
- Structured logging (JSONL, searchable)
- Local-first (no cloud vendor lock-in)

### 📊 **Observable**
- Real-time ControlBus (heartbeats, statistics)
- Live DAG visualization (web UI)
- Session-aware logging

---

## Real-World Examples

### Example 1: CI/CD Pipeline
```json
Build → Test → [Decision] → Deploy (if success) OR Alert (if fail)
```
Define once. Use in GitHub Actions, GitLab CI, Jenkins, or anywhere.

### Example 2: Data Processing
```json
Ingest → Validate → [Filter] → Transform → Load → Archive
         ↓ (on error)
         → Log Error → Send Alert
```
Visual, auditable, testable. No spaghetti code.

### Example 3: AI-Driven Development
```json
Format → Lint → Test
  ↑            ↓
  ← (if fail) ←
Any MCP-compatible AI agent auto-fixes and retries
```
AI never waits. Any MCP-compatible agent gets instant feedback and self-corrects (Claude Code, Claude API, or other agents).

---

## ⚡ 30-Second Quick Start (TL;DR)

Just want to try it? **One command:**

```bash
# 1. Download & run (native executable, no setup needed)
wget https://github.com/infenia/yukta/releases/download/v0.1.0/yukta-$(uname -s)-$(uname -m)
chmod +x yukta-*
./yukta-* &

# 2. Define a workflow (one curl, that's it)
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "demo", "description": "Quick demo", "initiator": "me", "workflows": { "hello": { "description": "Echo", "nodes": [{ "nodeId": "echo", "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } }], "edges": [] } } }'

# 3. Run it
curl -X POST http://localhost:8080/api/workflow/trigger \
  -d '{ "sessionId": "demo", "workflowId": "hello", "payload": {} }'

# 4. See it run (real-time status)
curl http://localhost:8080/api/workflow/demo/status/{executionId}
```

**Done.** That's a workflow orchestrator working. Ready for more? Read on. ↓

---

## 🚀 Getting Started (Choose Your Path)

### **Option 1: MCP Integration** (Recommended for AI-Driven Workflows)

Use Yukta with **any MCP-compatible AI agent** (Claude Code, Claude API, or any other AI that supports MCP) for instant, automated workflow execution.

```bash
# 1. Install Yukta (native executable, ~50MB, no JVM needed)
wget https://github.com/infenia/yukta/releases/download/v0.1.0/yukta-linux-x64
chmod +x yukta-linux-x64
./yukta-linux-x64

# 2. Configure your AI agent to use Yukta via MCP
# For Claude Code, add to .claude/settings.json:
{
  "mcpServers": {
    "yukta": {
      "command": "./yukta-linux-x64",
      "args": ["--mcp"]
    }
  }
}

# For Claude API or other agents, use the MCP protocol
# Endpoint: http://localhost:8080/sse
# Protocol: STATELESS, ASYNC

# 3. Your AI agent now has Yukta tools:
#    - create_session
#    - trigger_workflow
#    - get_workflow_status
#    - stream_session_logs
#    - get_workflow_execution_logs
#    - list_plugins
#    - get_plugin_details
#    - get_control_bus_status
#    ... and 5 more
```

**Result**: AI agent auto-runs workflows, detects failures, self-corrects. No polling. No webhooks. Works with any MCP-compatible AI.

---

### **Option 2: Native Executable** (Recommended for Production Deployments)

Zero JVM overhead. Instant startup. GraalVM ahead-of-time compilation.

```bash
# 1. Download native executable
wget https://github.com/infenia/yukta/releases/download/v0.1.0/yukta-macos-aarch64
chmod +x yukta-macos-aarch64
./yukta-macos-aarch64

# 2. Define your workflow
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "my-pipeline",
    "description": "Data ETL",
    "initiator": "devops",
    "projectPath": "/data",
    "workflows": {
      "ingest-transform-load": {
        "description": "ETL pipeline",
        "nodes": [
          { "nodeId": "validate", "type": "PROCESS_EXECUTOR", "config": { "command": ["./validate.sh"] } },
          { "nodeId": "transform", "type": "PROCESS_EXECUTOR", "config": { "command": ["./transform.sh"] } },
          { "nodeId": "load", "type": "PROCESS_EXECUTOR", "config": { "command": ["./load.sh"] } }
        ],
        "edges": [
          { "source": "validate", "target": "transform" },
          { "source": "transform", "target": "load" }
        ]
      }
    }
  }'

# 3. Run it
curl -X POST http://localhost:8080/api/workflow/trigger \
  -d '{ "sessionId": "my-pipeline", "workflowId": "ingest-transform-load", "payload": {} }'

# 4. Stream live status
curl http://localhost:8080/api/workflow/my-pipeline/status/{executionId}/stream
```

**Result**: Production-ready, sub-100ms feedback, observable workflows.

---

### **Option 3: Java Development** (For Contributors & Custom Plugins)

Traditional Spring Boot development with hot reload and full debugging.

```bash
# 1. Clone and build
git clone https://github.com/infenia/yukta.git
cd yukta
./gradlew bootRun

# 2. Open web UI
# http://localhost:8080/swagger-ui.html

# 3. Start developing
# - Add custom plugins (implements ProcessorPlugin)
# - Modify orchestration logic
# - Write tests with StepVerifier
```

**Result**: Full IDE debugging, instant hot reload, test-driven plugin development.

---

## Which Should I Choose?

| Use Case | Pick This | Why |
|----------|-----------|-----|
| **AI-driven development** (any MCP-compatible AI agent) | Option 1: MCP | Native MCP integration, instant feedback, self-correcting |
| **Production deployment** (K8s, CI/CD, Docker) | Option 2: Native Executable | 50MB executable, <100ms startup, zero JVM overhead |
| **Building custom plugins** (extending Yukta) | Option 3: Java Development | Full IDE support, testing, hot reload |
| **I don't know yet** | Option 1: MCP | Zero setup, works with any AI agent that supports MCP |

---

## Common Questions (We Get These a Lot)

**Q: Is this just for containers/K8s?**
A: No. Works locally, in Docker, on bare metal, in K8s, in serverless. Same workflow everywhere. Define once in JSON, run anywhere.

**Q: Do I have to learn a new language?**
A: No. Workflows are JSON. You describe the flow (nodes and edges), Yukta handles the orchestration. No special syntax.

**Q: What if a step fails?**
A: Yukta shows you exactly what failed (with error output). You choose: automatic retries, conditional branching to error-handling steps, or manual intervention.

**Q: Will this replace my CI/CD tool?**
A: No. Yukta **complements** your CI/CD (GitHub Actions, GitLab CI, Jenkins). Yukta is the "inside" orchestrator—it handles multi-step workflows, your CI/CD handles triggering, notifications, and secrets management.

**Q: Is it open-source?**
A: Yes. Apache 2.0 licensed. Self-hosted, local-first, no vendor lock-in. Run it wherever you want.

**Q: Can I use it with my current AI (Claude, ChatGPT, Gemini, others)?**
A: If your AI supports **MCP (Model Context Protocol)**, yes. We provide MCP natively. If not, use the REST API directly.

**Q: What's the learning curve?**
A: Very low. Define your first workflow in 5 minutes (just JSON). Get advanced in a day. Write custom plugins in a week.

**Q: How much does it cost?**
A: Nothing. It's open-source. Self-host it, no usage charges.

**Q: Can I run this in production?**
A: Absolutely. It's production-ready: input validation, session isolation, structured logging, error handling, and real-time monitoring.

**Q: What's the performance like?**
A: Sub-100ms feedback loops (reactive non-blocking). Handles 10k+ workflows/day with <100ms startup time. Scales from 1 engineer to 100+ concurrent workflows.

---

## 5-Minute Hands-On

**Want to try right now?**

```bash
# Using native executable
wget https://github.com/infenia/yukta/releases/download/v0.1.0/yukta-$(uname -s)-$(uname -m)
chmod +x yukta-*
./yukta-* &

# Trigger a simple workflow
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "demo", "description": "Quick demo", "initiator": "me", "workflows": { "hello": { "description": "Echo", "nodes": [{ "nodeId": "echo", "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } }], "edges": [] } } }'

curl -X POST http://localhost:8080/api/workflow/trigger \
  -d '{ "sessionId": "demo", "workflowId": "hello", "payload": {} }'

# See it run
curl http://localhost:8080/api/workflow/demo/status/{executionId}
```

**Done.** Three execution paths. Pick the one that fits your workflow.

---

## Why Yukta?

| Feature | Without Yukta | With Yukta |
|---------|---|---|
| **Define a workflow** | Write shell script (error-prone) | JSON DAG (visual, validated) |
| **Run it** | `./script.sh` in one place | REST API, MCP, web UI, anywhere |
| **See what's running** | Tail logs | Real-time status + DAG visualization |
| **Debug failures** | Grep logs, pray | Structured logs, execution traces |
| **Integrate with AI** | Webhooks + polling | Native MCP, instant feedback |
| **Reuse it** | Copy-paste (bad) | Store once, run everywhere |
| **Handle errors** | `if [ $? -eq 0 ]` | Conditional branching, retries |

---

## Built for Today's Challenges

- **Microservices**: Orchestrate across multiple services
- **AI-Driven Dev**: Any MCP-compatible AI agent + Yukta = auto-fixing workflows (Claude Code, Claude API, or others)
- **DevOps**: CI/CD without YAML hell
- **Data Pipelines**: ETL with visibility and control
- **Hybrid Workflows**: Mix humans and AI, real-time feedback

---

## Tech Stack (For the Curious)

- **Java 25** (reactive language features)
- **Spring Boot 4.0.2** (enterprise-grade stability)
- **Project Reactor** (non-blocking, sub-100ms latency)
- **GraalVM** (native image, 50MB executable)
- **JTE + Alpine.js** (interactive web UI)
- **MCP** (AI integration)

---

## What's Stable Today

✅ **DAG Orchestration** — Nodes, edges, branching, sub-workflows
✅ **REST API** — All core endpoints battle-tested
✅ **MCP Integration** — Claude Code fully supported
✅ **16 Built-in Plugins** — PROCESS_EXECUTOR, BRANCH, MAPPER, FILTER, SPLITTER, AGGREGATOR, and more
✅ **Session Management** — File or in-memory persistence
✅ **Live UI** — DAG visualization, execution history, control console

---

## Getting Started

- **New here?** Start with **[Getting Started Guide](docs/getting-started.md)** (5 min tutorial)
- **Want the details?** Read **[Architecture & Design](docs/architecture.md)** (deep dive)
- **Need API docs?** Check **[API Reference](docs/api-reference.md)** (all endpoints)
- **Building plugins?** See **[Plugin Development](docs/plugin-development.md)** (extend Yukta)
- **Contributing?** Read **[Development Setup](docs/development-setup.md)** (help us improve)

---

## Community & Support

- **Questions?** [Open an issue](https://github.com/infenia/yukta/issues/new?labels=question) or [start a discussion](https://github.com/infenia/yukta/discussions)
- **Found a bug?** [Report it](https://github.com/infenia/yukta/issues)
- **Want to contribute?** We'd love it! See [Contributing Guide](CONTRIBUTING.md)

### ⭐ Like Yukta? Consider:
- **Starring** the repo ❤️
- **Sharing** your workflow (Twitter, DEV.to, Reddit)
- **Contributing** (code, docs, plugins, feedback)
- **Sponsoring** development

---

## Security & License

- **Local-first**: No cloud vendor, no data exfil
- **Apache 2.0 licensed**: Transparent, auditable, enterprise-friendly
- **Input validated**: Blocks injection, path traversal, malicious input
- [Full Security Policy](SECURITY.md)

---

## Made With ❤️

**Yukta** is developed by **[Infenia Private Limited](https://infenia.com)**.

- **Creator**: Arun Cherthedath Somanathan ([arun@infenia.com](mailto:arun@infenia.com))

**Built on the belief that workflows should be simple, observable, and AI-friendly.**

---

## Fun Fact

*Yukta* (युक्त) is Sanskrit for **"united" or "joined"**—the essence of orchestration: bringing together disparate tools, systems, and agents into a cohesive, harmonious workflow. 🔗

---

**Ready to orchestrate?** ⚡

Choose your path:
- **🧠 AI-driven**: Set up MCP with any AI agent (Option 1)
- **🚀 Production**: Run native executable (Option 2)
- **👨‍💻 Development**: Clone repo and `./gradlew bootRun` (Option 3)

Then visit **[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)** to see your workflows in action.

Happy orchestrating! 🚀
