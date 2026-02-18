# Plugin Development Guide

Jagratha's power lies in its extensibility. You can extend its functionality by implementing three types of plugins: `JagrathaPlugin` (Build Tools), `OutputProcessorPlugin` (Log Parsers), and `AiPlugin` (AI Model Integrations).

## 1. JagrathaPlugin (Build Tool Integration)

A `JagrathaPlugin` defines how Jagratha interacts with a specific build tool (e.g., Gradle, Maven, NPM).

### Interface definition

```java
public interface JagrathaPlugin {
    String getName();
    String identifyModule(String projectRoot, String relativePath);
    List<String> buildTaskCommand(String module, String task, Map<String, Object> pluginConfig);
}
```

- `getName()`: Returns a unique identifier for the plugin (e.g., "gradle").
- `identifyModule()`: Given a file path, it should return the module it belongs to. For Gradle, this might be `:subproject-name`.
- `buildTaskCommand()`: Constructs the shell command to run a specific task (e.g., `["./gradlew", ":module:checkstyleMain"]`).

### Implementation Skeleton

```java
@Component
public class MyBuildPlugin implements JagrathaPlugin {
    @Override
    public String getName() {
        return "my-build-tool";
    }

    @Override
    public String identifyModule(String projectRoot, String relativePath) {
        // Logic to find which module the file belongs to
        return "";
    }

    @Override
    public List<String> buildTaskCommand(String module, String task, Map<String, Object> config) {
        return List.of("my-tool", module + ":" + task);
    }
}
```

---

## 2. OutputProcessorPlugin (Result Parsing)

This plugin type is used to transform raw console output from a build task into a structured format (usually JSONL) that an AI can easily consume.

### Interface definition

```java
public interface OutputProcessorPlugin {
    String getName();
    ProcessorResult process(ProcessorInput input);
}
```

- `process()`: Receives `ProcessorInput` containing the task output and configuration. It returns a `ProcessorResult` which includes a status, the processed output, and an optional path to a generated artifact.

### Implementation Skeleton

```java
@Component
public class MyLogProcessor implements OutputProcessorPlugin {
    @Override
    public String getName() {
        return "my-processor";
    }

    @Override
    public ProcessorResult process(ProcessorInput input) {
        String rawOutput = input.taskOutput();
        // 1. Parse rawOutput
        // 2. Transform to structured format (e.g., JSONL)
        String processed = "{\"issue\": \"...\"}";

        return new ProcessorResult("SUCCESS", processed, null);
    }
}
```

---

## 3. AiPlugin (AI Integration)

`AiPlugin` allows Jagratha to send data to an AI model and get feedback or suggestions.

### Interface definition

```java
public interface AiPlugin {
    String getName();
    String execute(String prompt, Map<String, Object> config);
}
```

- `execute()`: Takes a prompt and configuration, and returns the AI's response as a string.

### Implementation Skeleton

```java
@Component
public class MyAiPlugin implements AiPlugin {
    @Override
    public String getName() {
        return "my-ai-provider";
    }

    @Override
    public String execute(String prompt, Map<String, Object> config) {
        // Call your AI API here (OpenAI, Anthropic, Local Model, etc.)
        return "AI Feedback: " + prompt;
    }
}
```

## Registering Your Plugin

Since Jagratha is a Spring Boot application, you simply need to:
1. Implement the interface.
2. Annotate your class with `@Component`.
3. Jagratha will automatically discover and register your plugin at startup using Spring's dependency injection.

## Best Practices

1. **Immutability**: Always use immutable collections for configurations (e.g., `Map.copyOf`).
2. **Error Handling**: Throw descriptive exceptions or return a "FAILURE" status in the result objects.
3. **Logging**: Use SLF4J for logging. Follow the project convention of guarding complex logs: `if (log.isDebugEnabled()) { ... }`.
4. **Timeouts**: If your plugin performs external network calls (like an `AiPlugin`), ensure you respect the `executionTimeout` provided in the configuration.
