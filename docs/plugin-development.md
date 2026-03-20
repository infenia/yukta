# Plugin Development Guide

Yukta's extensibility comes from its plugin architecture. Plugins are the building blocks of workflows—they process messages reactively and compose into DAGs. Three types exist: **TriggerPlugin**, **ProcessorPlugin**, and **TerminalPlugin**.

---

## Plugin Lifecycle

All plugins follow this ordered lifecycle:

1. **validateConfig** — Validate configuration structure (field presence, types, constraints)
2. **initialize** — Called once before first execution (setup resources, connection pools)
3. **prepare** — Pre-execution (compile SpEL, load assets)
4. **start/process/consume** — Main execution (handle messages)
5. **shutdown** — Called once after last execution (cleanup)

All lifecycle methods return `Mono<Void>` (non-blocking).

---

## 1. TriggerPlugin (Workflow Entry Point)

A **TriggerPlugin** initiates a workflow. It has no incoming edges and must emit an initial `Flux<Message<?>>`.

### Interface

```java
public interface TriggerPlugin extends WorkflowPlugin {
    /**
     * Start workflow: emit messages that flow through downstream processors
     */
    Flux<Message<?>> start(Map<String, Object> config);
}
```

### Implementation Example

```java
@Component
public class ApiTriggerPlugin implements TriggerPlugin {
    @Override
    public String getType() {
        return "api-trigger";
    }

    @Override
    public PluginCategory getCategory() {
        return PluginCategory.TRIGGER;
    }

    @Override
    public Flux<Message<?>> start(Map<String, Object> config) {
        // For API trigger, we return an empty Flux initially
        // The orchestrator injects the payload when /api/workflow/trigger is called
        return Flux.just(
            DefaultMessage.create(UUID.randomUUID().toString(), config.get("payload"))
        );
    }

    @Override
    public Mono<Void> validateConfig(Map<String, Object> config) {
        // Validate required fields
        return Mono.empty();
    }

    @Override
    public Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
        // Ensure this node has no incoming edges (entry point)
        if (!context.getIncomingEdges(context.getNodeId()).isEmpty()) {
            return Mono.error(new ValidationException("Trigger cannot have incoming edges"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> prepare(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> initialize(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> shutdown(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public boolean isBlocking() {
        return false;
    }

    @Override
    public Mono<Message<?>> onControlSignal(Message<?> signal) {
        return Mono.just(signal);
    }

    @Override
    public List<String> getOutputPorts(Map<String, Object> config) {
        return List.of("default");
    }

    @Override
    public String getDescription() {
        return "HTTP POST listener trigger for REST workflows";
    }

    @Override
    public String getUsagePattern() {
        return "{ \"type\": \"api-trigger\", \"config\": {} }";
    }
}
```

### DAG Constraint

- **No incoming edges** (validates in step 2 of WorkflowValidator)

---

## 2. ProcessorPlugin (Transform/Route Messages)

A **ProcessorPlugin** transforms or routes messages. It must have both incoming and outgoing edges.

### Interface

```java
public interface ProcessorPlugin extends WorkflowPlugin {
    /**
     * Process incoming messages and emit transformed/routed messages
     */
    Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config);
}
```

### Implementation Example: PROCESS_EXECUTOR

Execute OS commands (Gradle, npm, bash, custom scripts):

```java
@Component
public class ProcessExecutorPlugin implements ProcessorPlugin {
    @Inject
    private BuildGateway buildGateway;

    @Override
    public String getType() {
        return "PROCESS_EXECUTOR";
    }

    @Override
    public PluginCategory getCategory() {
        return PluginCategory.PROCESSOR;
    }

    @Override
    public Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config) {
        return input.flatMap(msg -> {
            List<String> command = (List<String>) config.get("command");
            int timeout = (int) config.getOrDefault("timeout", 3600);
            Map<String, String> env = (Map<String, String>) config.get("env");
            String workingDir = (String) config.get("workingDir");

            return buildGateway.executeCommand(command, workingDir, env, timeout)
                .map(output -> msg.withPayload(output).withSourcePort("default"));
        });
    }

    @Override
    public Mono<Void> validateConfig(Map<String, Object> config) {
        if (!config.containsKey("command")) {
            return Mono.error(new ValidationException("'command' field is required"));
        }
        List<String> command = (List<String>) config.get("command");
        if (command == null || command.isEmpty()) {
            return Mono.error(new ValidationException("'command' cannot be empty"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
        // Processor must have outgoing edges
        if (context.getOutgoingEdges(context.getNodeId()).isEmpty()) {
            return Mono.error(new ValidationException("Processor must have outgoing edges"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> prepare(Map<String, Object> config) {
        // Pre-compile command string if using variables
        return Mono.empty();
    }

    @Override
    public Mono<Void> initialize(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> shutdown(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public boolean isBlocking() {
        return true;  // OS command execution is blocking I/O
    }

    @Override
    public Mono<Message<?>> onControlSignal(Message<?> signal) {
        return Mono.just(signal);
    }

    @Override
    public List<String> getOutputPorts(Map<String, Object> config) {
        return List.of("default");
    }

    @Override
    public String getDescription() {
        return "Execute OS commands (gradle, npm, bash) with timeout and environment variables";
    }

    @Override
    public String getUsagePattern() {
        return "{ \"type\": \"PROCESS_EXECUTOR\", \"config\": { \"command\": [\"./gradlew\", \"build\"], \"timeout\": 3600 } }";
    }
}
```

### Implementation Example: BRANCH (Multiple Output Ports)

Route messages based on SpEL conditions:

```java
@Component
public class BranchPlugin implements ProcessorPlugin {
    @Override
    public String getType() {
        return "BRANCH";
    }

    @Override
    public Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config) {
        String expression = (String) config.get("selector");
        Map<String, String> cases = (Map<String, String>) config.get("cases");

        return input.flatMap(msg -> {
            // Evaluate SpEL expression
            boolean result = evaluateExpression(expression, msg.getPayload());
            String port = result ? cases.get("true") : cases.get("false");

            return Mono.just(msg.withSourcePort(port));
        });
    }

    @Override
    public Mono<Void> validateConfig(Map<String, Object> config) {
        if (!config.containsKey("selector")) {
            return Mono.error(new ValidationException("'selector' field is required"));
        }
        if (!config.containsKey("cases")) {
            return Mono.error(new ValidationException("'cases' field is required"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
        Map<String, String> cases = (Map<String, String>) config.get("cases");
        // Verify that edges exist for each declared output port
        List<String> ports = getOutputPorts(config);
        for (String port : ports) {
            boolean hasEdge = context.getOutgoingEdges(context.getNodeId()).stream()
                .anyMatch(edge -> edge.getSourcePort().equals(port));
            if (!hasEdge) {
                return Mono.error(new ValidationException("Missing edge for port: " + port));
            }
        }
        return Mono.empty();
    }

    @Override
    public List<String> getOutputPorts(Map<String, Object> config) {
        Map<String, String> cases = (Map<String, String>) config.get("cases");
        return List.copyOf(cases.values());
    }

    // ... other lifecycle methods ...
}
```

### DAG Constraints

- **Must have incoming edges** (validated in step 3)
- **Must have outgoing edges** (validated in step 3)

---

## 3. TerminalPlugin (Workflow Sink)

A **TerminalPlugin** consumes messages and produces side effects (logging, webhooks, notifications). It has no outgoing edges.

### Interface

```java
public interface TerminalPlugin extends WorkflowPlugin {
    /**
     * Consume all messages and produce side effects
     */
    Mono<Void> consume(Flux<Message<?>> input, Map<String, Object> config);
}
```

### Implementation Example: CONSOLE_TERMINAL

Log output to console:

```java
@Component
public class ConsoleSinkPlugin implements TerminalPlugin {
    private static final Logger log = LoggerFactory.getLogger(ConsoleSinkPlugin.class);

    @Override
    public String getType() {
        return "console";
    }

    @Override
    public PluginCategory getCategory() {
        return PluginCategory.TERMINAL;
    }

    @Override
    public Mono<Void> consume(Flux<Message<?>> input, Map<String, Object> config) {
        return input
            .doOnNext(msg -> log.info("Output: {}", msg.getPayload()))
            .then();
    }

    @Override
    public Mono<Void> validateConfig(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
        // Terminal must have no outgoing edges
        if (!context.getOutgoingEdges(context.getNodeId()).isEmpty()) {
            return Mono.error(new ValidationException("Terminal cannot have outgoing edges"));
        }
        return Mono.empty();
    }

    // ... other lifecycle methods ...
}
```

### DAG Constraint

- **No outgoing edges** (validated in step 4 of WorkflowValidator)

---

## 4. Message<T> API

All plugins communicate via immutable `Message<T>` envelopes. **Never mutate messages directly**—use wither methods.

### Core Methods

```java
// Get/Set payload
T getPayload();
<R> Message<R> withPayload(R newPayload);

// Routing
String getSourcePort();
Message<?> withSourcePort(String port);

// Headers/Metadata
Object getHeader(String key);
Message<?> withHeader(String key, Object value);
Map<String, Object> getMetadata();

// Tracing
String getTraceId();
String getMessageId();
String getCorrelationId();

// Sequence tracking (for Splitter/Aggregator)
String getSequenceId();
Integer getSequenceNumber();
Integer getSequenceSize();
```

### Factory Methods

```java
// Create new message
Message<?> msg = DefaultMessage.create(traceId, payload);

// Copy with new payload
Message<?> transformed = DefaultMessage.from(original, newPayload);
```

### Example: Transform Payload

```java
Message<?> transformed = input
    .withPayload(newData)
    .withSourcePort("success")
    .withHeader("X-Custom-Header", "value")
    .withAddedHistory(nodeId);
```

---

## 5. Configuration Validation

### validateConfig (Field-Level)

Validate configuration structure **before** DAG context is available:

```java
@Override
public Mono<Void> validateConfig(Map<String, Object> config) {
    // Check required fields
    if (!config.containsKey("command")) {
        return Mono.error(new ValidationException("'command' field is required"));
    }

    // Check types
    Object cmd = config.get("command");
    if (!(cmd instanceof List)) {
        return Mono.error(new ValidationException("'command' must be a list"));
    }

    // Check constraints
    int timeout = (int) config.getOrDefault("timeout", 3600);
    if (timeout <= 0) {
        return Mono.error(new ValidationException("'timeout' must be > 0"));
    }

    return Mono.empty();
}
```

### validateInContext (DAG-Level)

Validate structural constraints **after** DAG is built (access incoming/outgoing edges):

```java
@Override
public Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
    // Check edge requirements
    List<Edge> outgoing = context.getOutgoingEdges(context.getNodeId());
    if (outgoing.isEmpty()) {
        return Mono.error(new ValidationException("Processor must have outgoing edges"));
    }

    // Validate edge ports match declared ports
    List<String> ports = getOutputPorts(config);
    for (Edge edge : outgoing) {
        if (!ports.contains(edge.getSourcePort())) {
            return Mono.error(new ValidationException(
                "Edge port '" + edge.getSourcePort() + "' not declared in plugin"
            ));
        }
    }

    return Mono.empty();
}
```

---

## 6. Output Ports (Multiple Routing Paths)

Declare multiple output ports for conditional routing (e.g., BRANCH plugin):

```java
@Override
public List<String> getOutputPorts(Map<String, Object> config) {
    // BRANCH plugin declares success/error ports based on config
    Map<String, String> cases = (Map<String, String>) config.get("cases");
    return List.copyOf(cases.values());
}
```

**Usage in DAG**:
```json
{
  "edges": [
    { "source": "test", "target": "deploy", "sourcePort": "success" },
    { "source": "test", "target": "alert", "sourcePort": "error" }
  ]
}
```

---

## 7. ControlBus Integration

Receive admin commands from the control bus:

```java
@Override
public Mono<Message<?>> onControlSignal(Message<?> signal) {
    // signal contains control metadata (e.g., heartbeat, admin command)
    String commandType = signal.getHeader("type").toString();

    if ("PAUSE".equals(commandType)) {
        // Pause execution
        return Mono.empty();
    }

    // Echo back or handle command
    return Mono.just(signal);
}
```

---

## 8. Self-Documenting Plugins

Implement `getDescription()` and `getUsagePattern()` for AI agent discoverability:

```java
@Override
public String getDescription() {
    return "Execute OS commands (gradle, npm, bash) with timeout and environment variables";
}

@Override
public String getUsagePattern() {
    return "{ \"type\": \"PROCESS_EXECUTOR\", \"config\": { "
        + "\"command\": [\"./gradlew\", \"build\"], "
        + "\"timeout\": 3600 } }";
}
```

These are exposed via `GET /api/plugins/{type}` and MCP tools.

---

## 9. Registration

Since Yukta is a Spring Boot application, registration is automatic:

1. Implement `TriggerPlugin`, `ProcessorPlugin`, or `TerminalPlugin`
2. Annotate class with `@Component`
3. Add to `boot/build.gradle` if in a separate module (via dependency)
4. Yukta discovers at startup via `WorkflowRegistry`

---

## 10. Built-in Plugin Reference

| Type String | Category | Purpose | Required Config | Output Ports |
|-------------|----------|---------|-----------------|--------------|
| `api-trigger` | TRIGGER | HTTP POST listener | (none) | default |
| `CONSTANT_SOURCE` | TRIGGER | Emit constant payload | payload | default |
| `PROCESS_EXECUTOR` | PROCESSOR | Execute OS commands | command | default |
| `BRANCH` | PROCESSOR | SpEL-based routing | selector, cases | dynamic (from cases) |
| `FILTER` | PROCESSOR | Pass/reject by condition | expression | default |
| `MAPPER` | PROCESSOR | Transform via SpEL | expression | default |
| `CONTENT-FILTER` | PROCESSOR | Filter fields | fields | default |
| `ENRICHER` | PROCESSOR | Add metadata | metadata | default |
| `SPLITTER` | PROCESSOR | Split 1→N | expression | default |
| `AGGREGATOR` | PROCESSOR | Combine N→1 | aggregationStrategy | default |
| `RESEQUENCER` | PROCESSOR | Enforce sequence | timeout | default |
| `RECIPIENT_LIST` | PROCESSOR | Route to N | expression | dynamic |
| `LOOP_PREDICATE` | PROCESSOR | Retry until condition | expression, maxIterations | default |
| `LOOP_STREAM` | PROCESSOR | Iterate collection | expression | default |
| `SUB_WORKFLOW` | PROCESSOR | Nested workflow | workflowId, sessionId | default |
| `console` | TERMINAL | Log output | (none) | (none) |

---

## Best Practices

1. **Immutability**: Never mutate message state; use `withXxx()` methods
2. **Error Handling**: Return `Mono.error()` from validation, not exceptions
3. **Config Validation**: Validate in `validateConfig()`, not at runtime
4. **Pre-Compilation**: Pre-compile SpEL/regex in `prepare()`, not in `process()`
5. **Blocking Detection**: Set `isBlocking() = true` for I/O operations (files, network, databases)
6. **Logging**: Use SLF4J; guard debug logs: `if (log.isDebugEnabled()) { ... }`
7. **Metadata**: Use `withHeader()` for metadata; avoid large payloads in headers
8. **Statelessness**: Plugins are singletons; no mutable instance state
9. **Timeouts**: Respect timeout configurations; use `Mono.timeout()` for async operations
10. **Testing**: Write unit tests using `StepVerifier` for reactive streams

---

## Example: Custom Plugin

```java
@Component
public class MyCustomPlugin implements ProcessorPlugin {
    private static final Logger log = LoggerFactory.getLogger(MyCustomPlugin.class);

    @Override
    public String getType() {
        return "MY_CUSTOM_PLUGIN";
    }

    @Override
    public PluginCategory getCategory() {
        return PluginCategory.PROCESSOR;
    }

    @Override
    public Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config) {
        return input.flatMap(msg -> {
            // Transform payload
            Object payload = msg.getPayload();
            Object transformed = doTransform(payload);
            return Mono.just(msg.withPayload(transformed));
        });
    }

    @Override
    public Mono<Void> validateConfig(Map<String, Object> config) {
        // Validate required fields
        return Mono.empty();
    }

    @Override
    public Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> prepare(Map<String, Object> config) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> initialize(Map<String, Object> config) {
        log.info("Initializing MyCustomPlugin");
        return Mono.empty();
    }

    @Override
    public Mono<Void> shutdown(Map<String, Object> config) {
        log.info("Shutting down MyCustomPlugin");
        return Mono.empty();
    }

    @Override
    public boolean isBlocking() {
        return false;
    }

    @Override
    public Mono<Message<?>> onControlSignal(Message<?> signal) {
        return Mono.just(signal);
    }

    @Override
    public List<String> getOutputPorts(Map<String, Object> config) {
        return List.of("default");
    }

    @Override
    public String getDescription() {
        return "My custom plugin description";
    }

    @Override
    public String getUsagePattern() {
        return "{ \"type\": \"MY_CUSTOM_PLUGIN\", \"config\": { } }";
    }

    private Object doTransform(Object payload) {
        // Your transformation logic here
        return payload;
    }
}
```
