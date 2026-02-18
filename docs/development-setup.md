# Development Setup

This document describes how to set up your local environment for contributing to Jagratha.

## Prerequisites

### 1. Java 25 (with Java 21 Toolchain)

Jagratha is designed for **Java 25**, but it currently uses a **Java 21 toolchain** for compatibility in environments where Java 25 is not yet natively available.

We recommend using [SDKMAN!](https://sdkman.io/) to manage your Java versions.

```bash
# Install SDKMAN! if you haven't already
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java 21 (Temurin)
sdk install java 21.0.6-tem
```

### 2. Gradle 9.0

Jagratha uses the Gradle Wrapper, so you don't need to install Gradle globally. However, if you wish to do so:

```bash
sdk install gradle 9.0
```

---

## Build & Test

### Compile the project

```bash
./gradlew build
```

This will run:
- Compilation
- Unit tests
- Static analysis (Checkstyle, PMD, SpotBugs)
- JaCoCo coverage check (80% minimum required)

### Run only tests

```bash
./gradlew test
```

### Apply code formatting

Jagratha uses **Spotless** with **Google Java Format**. To automatically fix formatting issues:

```bash
./gradlew spotlessApply
```

---

## IDE Configuration

### IntelliJ IDEA

1. **Open the project**: File > Open > Select `build.gradle`.
2. **Project SDK**: Set to Java 21.
3. **Annotation Processors**:
   - Enable annotation processing in `Settings > Build, Execution, Deployment > Compiler > Annotation Processors`.
   - Jagratha uses **Lombok** and **MapStruct**, which require this.
4. **Plugins**: Install the **Lombok** plugin and the **Checkstyle-IDEA** plugin for real-time feedback.

---

## GraalVM Native Image

Jagratha supports building native binaries using GraalVM.

### 1. Install GraalVM

```bash
sdk install java 21.0.6-graal
```

### 2. Build the Native Image

```bash
./gradlew nativeCompile
```

The resulting binary will be located in `build/native/nativeCompile/jagratha`.

---

## Troubleshooting

- **Checkstyle/PMD Failures**: Run `./gradlew spotlessApply` first. If it still fails, check the reports in `build/reports/checkstyle/` and `build/reports/pmd/`.
- **Lombok Errors**: Ensure you have enabled annotation processing in your IDE.
- **Dependency Issues**: Try clearing the Gradle cache: `./gradlew clean`.
