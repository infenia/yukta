# Jagratha 🛡️

**Jagratha** (Sanskrit for *Vigilance*) is a high-performance server designed to enforce code quality gates for AI-driven development.

It hosts a **Model Context Protocol (MCP)** server, accepts **Agent Client Protocol (ACP)** tool interactions, validates code changes, enforces quality gates (Spotless, Checkstyle, PMD), and provides structured feedback to AI agents.

---

## 🚀 Key Features

- **AI Orchestration**: Seamlessly integrates with AI agents like Claude Code to validate changes autonomously.
- **Quality Gates**: Enforces strict coding standards using Spotless, Checkstyle, PMD, SpotBugs, and JaCoCo.
- **Extensible Plugin System**: Support for any build tool (Gradle, Maven, NPM) and custom AI feedback models.
- **MCP Native**: Native support for Model Context Protocol, allowing LLMs to "see" and "run" quality checks directly.
- **Detailed Logging**: Session-aware, JSONL-formatted logs for easy consumption by AI models.
- **High Performance**: Built on Spring Boot WebFlux (Reactive) for non-blocking operations.

---

## 🛠️ Tech Stack

- **Java 25** (Targets Java 25, using Java 21 toolchain for compatibility)
- **Spring Boot 4.0.2** (WebFlux, Actuator, Spring AI)
- **Gradle 9.0**
- **GraalVM** (Native Image support)
- **OpenAPI 3.0** (Springdoc Swagger UI)

---

## 📖 Documentation

- **[Getting Started](docs/getting-started.md)** - Run your first quality check in 5 minutes.
- **[Architecture & Design](docs/architecture.md)** - Detailed diagrams and internal mechanisms.
- **[API Reference](docs/api-reference.md)** - Swagger UI and endpoint details.
- **[Plugin Development](docs/plugin-development.md)** - Guide on extending Jagratha with new tools and models.
- **[Development Setup](docs/development-setup.md)** - Instructions for contributors.

---

## 🚦 Quick Start

### 1. Start the server
```bash
./gradlew bootRun
```

### 2. Access Swagger UI
Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) to explore the API.

---

## 🤝 Contributing

We welcome contributions! Please see our **[Contributing Guide](CONTRIBUTING.md)** and **[Code of Conduct](CODE_OF_CONDUCT.md)** for more details.

---

## 🛡️ Security

If you discover a security vulnerability, please see our **[Security Policy](SECURITY.md)**.

---

## 🏢 Attribution

Jagratha is developed and maintained by **[Infenia Private Limited](https://infenia.com)**.

- **Developer**: Arun Cherthedath Somanathan (**arun@infenia.com**)

---

## 📄 License

This project is licensed under the **Apache License, Version 2.0** - see the [LICENSE](LICENSE) file for details.
