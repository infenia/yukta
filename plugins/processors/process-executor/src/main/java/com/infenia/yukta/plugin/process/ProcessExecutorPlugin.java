// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.VariableResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import reactor.util.function.Tuple5;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Processor plugin for executing external processes.
 *
 * <p>Supports variable resolution in command, environment, and working directory. The output
 * message payload is configurable via {@code outputFormat} (structured map, raw stdout, parsed
 * JSON, or legacy passthrough) and failure handling via {@code failureMode} (fail the node or emit
 * the result with the real exit code so downstream nodes can route on it).
 */
@SuppressWarnings({
  "PMD.CouplingBetweenObjects",
  "PMD.CyclomaticComplexity",
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.CommentRequired",
  "PMD.OnlyOneReturn",
  "PMD.UseConcurrentHashMap",
  "PMD.InsufficientStringBufferDeclaration",
  "PMD.ExceptionAsFlowControl",
  "PMD.ExcessiveImports"
})
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "injected collaborator")
@Slf4j
@Component
public final class ProcessExecutorPlugin implements ProcessorPlugin {

  /** Extra time the node gets beyond the process timeout, in seconds. */
  private static final long MARGIN_SECONDS = 5L;

  private static final String TYPE = "PROCESS_EXECUTOR";

  private static final String COMMAND = "command";
  private static final String TIMEOUT = "timeout";

  /** Messages handled at once when the node does not set {@code concurrency}. */
  private static final int DEFAULT_CONCURRENCY = 4;

  private static final String OUTPUT_FORMAT = "outputFormat";
  private static final String FAILURE_MODE = "failureMode";
  private static final String INPUT_MODE = "inputMode";
  private static final String ROUTE_BY_EXIT_CODE = "routeByExitCode";
  private static final String METADATA_ENV_PREFIX = "YUKTA_METADATA_";
  private static final String PAYLOAD_ENV_KEY = "YUKTA_PAYLOAD";

  private static final String PORT_SUCCESS = "success";
  private static final String PORT_FAILURE = "failure";

  private static final String EXIT_CODE_KEY = "exitCode";
  private static final String OUTPUT_KEY = "output";

  private static final long DEFAULT_TIMEOUT_SECONDS = 300L;
  private static final int DEFAULT_MAX_OUTPUT_LINES = 10_000;
  private static final long DEFAULT_MAX_OUTPUT_BYTES = 10L * 1024 * 1024;

  /** Lines of process output kept in a failure message. */
  private static final int ERROR_OUTPUT_LINES = 50;

  /** Characters of process output kept in a failure message. */
  private static final int ERROR_OUTPUT_CHARS = 8192;

  private final ProcessExecutorGateway gateway;
  private final VariableResolver resolver;
  private final PluginMetricsReporter reporter;
  private final ObjectMapper json;

  /**
   * Creates the plugin without metrics.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   */
  public ProcessExecutorPlugin(
      final ProcessExecutorGateway gateway, final VariableResolver resolver) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = PluginMetricsReporter.NOOP;
    this.json = new ObjectMapper();
  }

  /**
   * Spring constructor.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   * @param reporterProvider optional metrics reporter
   * @param json the application JSON mapper
   */
  @Autowired
  public ProcessExecutorPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final ObjectProvider<PluginMetricsReporter> reporterProvider,
      final ObjectMapper json) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
    this.json = json;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Executes external processes and emits a configurable result (exit code, output, "
            + "duration). Supports multi-OS (Linux, macOS, Windows) with shell wrapping, stderr "
            + "capture, output caps, and proper cleanup on timeout/error.",
        usagePattern(),
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required("command", ConfigField.Type.LIST, "Command and arguments"),
        ConfigField.choice(
            "inputMode",
            false,
            "NONE",
            "How the message reaches the process",
            "NONE",
            "ENV",
            "STDIN"),
        ConfigField.optional("workingDir", ConfigField.Type.STRING, null, "Working directory"),
        ConfigField.optional("timeout", ConfigField.Type.NUMBER, 300, "Timeout in seconds"),
        ConfigField.optional("env", ConfigField.Type.MAP, null, "Extra environment variables"),
        ConfigField.optional(
            "useShell",
            ConfigField.Type.BOOLEAN,
            false,
            "Run the elements as one shell script (pipes, &&, > work); needs"
                + " yukta.process.allow-any"),
        ConfigField.optional("captureStderr", ConfigField.Type.BOOLEAN, null, "Capture stderr"),
        ConfigField.choice(
            "outputFormat",
            false,
            "STRUCTURED",
            "Shape of the emitted payload",
            "STRUCTURED",
            "RAW",
            "JSON",
            "PASSTHROUGH"),
        ConfigField.choice(
            "failureMode", false, "ERROR", "What to do on a non-zero exit", "ERROR", "CONTINUE"),
        ConfigField.optional(
            "routeByExitCode",
            ConfigField.Type.BOOLEAN,
            false,
            "Emit on success/failure ports (needs failureMode CONTINUE)"),
        ConfigField.optional(
            ConfigValidation.CONCURRENCY_KEY,
            ConfigField.Type.NUMBER,
            DEFAULT_CONCURRENCY,
            "Messages handled at once (each starts a process)"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  private static String usagePattern() {
    // CHECKSTYLE.OFF: Indentation
    // CHECKSTYLE.OFF: TextBlockGoogleStyleFormatting
    return """
    Configuration Parameters:

    REQUIRED:
    - command: List<String> (variable expressions supported)
      Command and arguments to execute
      Example: ["mvn", "clean", "test"] or ["deploy", "${payload.version}"]

    VARIABLE EXPRESSIONS (in command, workingDir, timeout, env):
    - ${env.NAME}, ${sys.prop}, ${context.var}: environment/system/reactor-context values
    - ${payload}, ${payload.some.field}: the input message payload (nested map access)
    - ${metadata.someKey}: an input message metadata entry
      Keys containing PASSWORD/SECRET/KEY are blocked for safety

    OPTIONAL (input):
    - inputMode: NONE | ENV | STDIN (default: NONE)
      ENV: metadata exported as YUKTA_METADATA_* env vars and payload as YUKTA_PAYLOAD
           (String payloads as-is, other payloads JSON-serialized); explicit env wins.
           SECURITY: env vars are visible to child processes and process listings -
           do not use with messages carrying secrets
      STDIN: payload written to the process standard input
             (String payloads as-is, other payloads JSON-serialized)

    OPTIONAL (execution):
    - workingDir: String (SpEL supported, default: current directory)
    - timeout: Long (SpEL supported, default: 300 seconds, must be positive)
    - env: Map<String, String> (SpEL supported, default: empty)
    - useShell: true | false (default: false; not an expression)
      Run the command elements as one shell script (sh -c on Unix, cmd.exe /c on Windows),
      joined with spaces and run as written, so "|", "&&" and ">" work. An element that uses
      ${...} becomes one quoted word, so message data cannot add shell syntax; put shell syntax
      in elements without variables. Needs yukta.process.allow-any=true. On Windows a variable's
      value cannot contain a double quote, "%" or a line break (use inputMode ENV or STDIN).
      Example: ["grep", "-c", "${payload.query}", "src/*.java", "|", "sort"]
    - captureStderr: Boolean (default: false)
      Capture stderr separately instead of merging it into stdout
    - concurrency: Integer (default: 4, must be positive)
      Messages handled at once; each one starts a process, the rest wait their turn
    - maxOutputLines: Integer (default: 10000, 0 = unlimited)
    - maxOutputBytes: Long (default: 10485760, 0 = unlimited)
      Output beyond the caps is discarded and the result is flagged as truncated

    OPTIONAL (output):
    - outputFormat: STRUCTURED | RAW | JSON | PASSTHROUGH (default: STRUCTURED)
      STRUCTURED: payload = {exitCode, success, timedOut, durationMillis, outputTruncated,
                             stdout?, stderr?, input?}
      RAW: payload = process stdout as a single string
      JSON: STRUCTURED plus "output" = stdout parsed as JSON (null if the process failed)
      PASSTHROUGH: input payload forwarded unchanged (legacy behavior)
    - failureMode: ERROR | CONTINUE (default: ERROR)
      ERROR: non-zero exit code or timeout fails the workflow node
      CONTINUE: the result message is emitted with the real exit code so downstream
                nodes can route on it (e.g. payload.exitCode == 0)
    - routeByExitCode: Boolean (default: false, requires failureMode: CONTINUE)
      Stamps output messages with source port "success" (exit code 0) or "failure"
      (non-zero exit or timeout). Wire edges with sourcePort success/failure to route
      without a separate BRANCH node; edges without a sourcePort receive all messages.
    - includeOutput: Boolean (default: true)
      Include stdout/stderr text in STRUCTURED/JSON payloads
    - includeInput: Boolean (default: false)
      Include the original input payload under "input" in STRUCTURED/JSON payloads

    OUTPUT:
    - Message metadata always contains "exitCode" (real exit code; -1 on timeout)
    - JSON parse failures follow failureMode (ERROR: node fails; CONTINUE: "parseError" is set)
    """;
  }

  /**
   * The node timeout follows the process timeout, plus a margin for starting and collecting it.
   *
   * @param config the node configuration
   * @return the configured (or default) process timeout plus a margin
   */
  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    final long seconds =
        config.get(TIMEOUT) instanceof final Number number && number.longValue() > 0
            ? number.longValue()
            : DEFAULT_TIMEOUT_SECONDS;
    return Duration.ofSeconds(seconds + Math.max(MARGIN_SECONDS, seconds / 10));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    if (MapUtils.convert(config.getOrDefault(ROUTE_BY_EXIT_CODE, false), Boolean.class)) {
      return List.of(PORT_SUCCESS, PORT_FAILURE);
    }
    return getOutputPorts();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    log.atDebug().log("Process executor starting: processing input stream");
    return PluginGuard.counted(
        input.flatMapSequential(
            message -> executeProcess(message, config),
            ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY)),
        reporter,
        "process");
  }

  private Flux<Message<?>> executeProcess(
      final Message<?> message, final Map<String, Object> config) {
    log.atDebug().log("Starting executeProcess for message");
    return resolveConfig(config, message)
        .flatMapMany(resolvedConfig -> executeWithConfig(message, resolvedConfig))
        .doOnError(
            error ->
                log.atError()
                    .setCause(error)
                    .log("Process executor failed: {}", error.getMessage()));
  }

  private Flux<Message<?>> executeWithConfig(
      final Message<?> message, final ProcessExecutorConfig config) {
    log.atInfo().log(
        "Starting process execution: timeout={}s, workingDir={}",
        config.timeout(),
        config.workingDir());

    return Mono.deferContextual(
            ctx -> {
              final ProcessExecutionSpec spec =
                  ProcessExecutionSpec.builder()
                      .command(config.command())
                      .workingDir(config.workingDir())
                      .timeoutSeconds(config.timeout())
                      .env(withRunIds(buildEnv(message, config), ctx))
                      .useShell(config.useShell())
                      .stdin(buildStdin(message, config))
                      .captureStderr(config.captureStderr())
                      .maxOutputLines(config.maxOutputLines())
                      .maxOutputBytes(config.maxOutputBytes())
                      .build();
              return ctx.<PluginLogSink>getOrEmpty(WorkflowContext.LOG_SINK_KEY)
                  .map(sink -> gateway.executeForResult(spec, sink))
                  .orElseGet(() -> gateway.executeForResult(spec));
            })
        .flatMapMany(result -> mapResult(message, config, result))
        .onErrorMap(error -> handleExecutionError(config, error));
  }

  private Flux<Message<?>> mapResult(
      final Message<?> message,
      final ProcessExecutorConfig config,
      final ProcessExecutionResult result) {
    if (!result.isSuccess() && config.failureMode() == FailureMode.ERROR) {
      return Flux.error(executionFailure(config, result));
    }
    log.atInfo().log(
        "Process completed: exitCode={}, timedOut={}, durationMillis={}",
        result.exitCode(),
        result.timedOut(),
        result.durationMillis());
    return Flux.defer(
        () ->
            Flux.just(
                outputMessage(message, config, result, buildPayload(message, config, result))));
  }

  private Object buildPayload(
      final Message<?> message,
      final ProcessExecutorConfig config,
      final ProcessExecutionResult result) {
    return switch (config.outputFormat()) {
      case RAW -> result.stdout();
      case PASSTHROUGH -> message.payload();
      case STRUCTURED -> structuredPayload(message, config, result);
      case JSON -> jsonPayload(message, config, result);
    };
  }

  private Map<String, Object> structuredPayload(
      final Message<?> message,
      final ProcessExecutorConfig config,
      final ProcessExecutionResult result) {
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(EXIT_CODE_KEY, result.exitCode());
    payload.put("success", result.isSuccess());
    payload.put("timedOut", result.timedOut());
    payload.put("durationMillis", result.durationMillis());
    payload.put("outputTruncated", result.outputTruncated());
    if (config.includeOutput()) {
      payload.put("stdout", result.stdout());
      payload.put("stderr", result.stderr());
    }
    if (config.includeInput()) {
      payload.put("input", message.payload());
    }
    return payload;
  }

  private Map<String, Object> jsonPayload(
      final Message<?> message,
      final ProcessExecutorConfig config,
      final ProcessExecutionResult result) {
    final Map<String, Object> payload = structuredPayload(message, config, result);
    if (!result.isSuccess()) {
      payload.put(OUTPUT_KEY, null);
      return payload;
    }
    try {
      payload.put(OUTPUT_KEY, json.readValue(result.stdout(), Object.class));
    } catch (JacksonException e) {
      if (config.failureMode() == FailureMode.ERROR) {
        throw new WorkflowExecutionException(
            "Failed to parse process output as JSON: " + e.getMessage(), e);
      }
      log.atWarn()
          .setMessage("Process output is not valid JSON; continuing with parseError set")
          .setCause(e)
          .log();
      payload.put(OUTPUT_KEY, null);
      payload.put("parseError", e.getMessage());
    }
    return payload;
  }

  /**
   * Adds the ids of the run to the environment of the process, so a script can steer its own run
   * (pause it, read its tree): {@code YUKTA_EXECUTION_ID} and {@code YUKTA_SESSION_ID}. An entry
   * the workflow configures with the same name wins.
   */
  private static Map<String, String> withRunIds(
      final Map<String, String> env, final ContextView ctx) {
    final Map<String, String> all = new HashMap<>();
    ctx.<String>getOrEmpty("executionId").ifPresent(id -> all.put("YUKTA_EXECUTION_ID", id));
    ctx.<String>getOrEmpty("sessionId").ifPresent(id -> all.put("YUKTA_SESSION_ID", id));
    all.putAll(env);
    return all;
  }

  private Map<String, String> buildEnv(
      final Message<?> message, final ProcessExecutorConfig config) {
    if (config.inputMode() != InputMode.ENV) {
      return config.env();
    }
    final Map<String, String> env = new HashMap<>();
    message
        .metadata()
        .forEach(
            (key, value) -> {
              if (value != null) {
                final String envKey =
                    METADATA_ENV_PREFIX + key.toUpperCase(Locale.ROOT).replace('.', '_');
                env.put(envKey, value.toString());
              }
            });
    if (message.payload() != null) {
      env.put(PAYLOAD_ENV_KEY, payloadAsString(message.payload()));
    }
    // Explicitly configured env entries take precedence over exported message values
    env.putAll(config.env());
    log.atDebug().log("Input message exported as environment: {} variable(s)", env.size());
    return env;
  }

  private String buildStdin(final Message<?> message, final ProcessExecutorConfig config) {
    if (config.inputMode() != InputMode.STDIN || message.payload() == null) {
      return null;
    }
    return payloadAsString(message.payload());
  }

  private String payloadAsString(final Object payload) {
    if (payload instanceof final String text) {
      return text;
    }
    try {
      return json.writeValueAsString(payload);
    } catch (JacksonException e) {
      throw new WorkflowExecutionException(
          "Failed to serialize input payload for inputMode: " + e.getMessage(), e);
    }
  }

  private Message<?> outputMessage(
      final Message<?> original,
      final ProcessExecutorConfig config,
      final ProcessExecutionResult result,
      final Object payload) {
    final Map<String, Object> newMetadata = new HashMap<>(original.metadata());
    newMetadata.put(EXIT_CODE_KEY, result.exitCode());
    log.atDebug().log("Exit code stored in metadata: {}", result.exitCode());
    final Message<?> output = DefaultMessage.from(original, payload).withMetadata(newMetadata);
    if (config.routeByExitCode()) {
      final String port = result.isSuccess() ? PORT_SUCCESS : PORT_FAILURE;
      log.atDebug().log("Routing message to output port: {}", port);
      return output.withSourcePort(port);
    }
    return output;
  }

  private WorkflowExecutionException executionFailure(
      final ProcessExecutorConfig config, final ProcessExecutionResult result) {
    if (result.timedOut()) {
      return new WorkflowExecutionException("Process timed out after " + config.timeout() + "s");
    }
    final StringBuilder message =
        new StringBuilder("Process failed with exit code ")
            .append(result.exitCode())
            .append("\n--- Output ---\n")
            .append(tail(result.stdout()));
    if (!result.stderrLines().isEmpty()) {
      message.append("\n--- Stderr ---\n").append(tail(result.stderr()));
    }
    return new WorkflowExecutionException(message.toString());
  }

  /**
   * The end of process output, for an error message: up to 10 MiB of output would otherwise go into
   * every log and event that carries the error. The full output stays in the node's output.
   */
  private static String tail(final String output) {
    final String[] lines = output.split("\n", -1);
    final String lastLines =
        lines.length > ERROR_OUTPUT_LINES
            ? String.join(
                "\n", Arrays.copyOfRange(lines, lines.length - ERROR_OUTPUT_LINES, lines.length))
            : output;
    final String shortened =
        lastLines.length() > ERROR_OUTPUT_CHARS
            ? lastLines.substring(lastLines.length() - ERROR_OUTPUT_CHARS)
            : lastLines;
    return shortened.length() == output.length()
        ? output
        : "[... " + (output.length() - shortened.length()) + " characters cut ...]\n" + shortened;
  }

  private Throwable handleExecutionError(
      final ProcessExecutorConfig config, final Throwable error) {
    log.atError()
        .setMessage("Process execution failed: workingDir={}, error type={}")
        .addArgument(config.workingDir())
        .addArgument(error.getClass().getSimpleName())
        .setCause(error)
        .log();
    return error;
  }

  @SuppressWarnings("unchecked")
  private Mono<ProcessExecutorConfig> resolveConfig(
      final Map<String, Object> config, final Message<?> message) {
    final boolean useShell;
    try {
      useShell = useShell(config);
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }
    return Mono.zip(
            resolveCommand((List<Object>) config.get(COMMAND), message, useShell),
            resolveWorkingDir(config, message),
            resolveTimeout(config, message),
            resolveEnv((Map<String, Object>) config.getOrDefault("env", Map.of()), message),
            Mono.just(useShell))
        .map(tuple -> buildConfig(tuple, config));
  }

  private ProcessExecutorConfig buildConfig(
      final Tuple5<List<String>, String, Long, Map<String, String>, Boolean> tuple,
      final Map<String, Object> config) {
    final long timeout = tuple.getT3();
    if (timeout <= 0) {
      throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
    }
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    final boolean routeByExitCode = booleanOption(config, ROUTE_BY_EXIT_CODE, false);
    if (routeByExitCode && failureMode != FailureMode.CONTINUE) {
      throw new IllegalArgumentException(
          "routeByExitCode requires failureMode: CONTINUE (failed processes error out otherwise,"
              + " so the failure port would never emit)");
    }
    final String workingDir = tuple.getT2();
    return ProcessExecutorConfig.builder()
        .command(tuple.getT1())
        .workingDir(workingDir.isEmpty() ? null : workingDir)
        .timeout(timeout)
        .env(tuple.getT4())
        .useShell(tuple.getT5())
        .outputFormat(OutputFormat.from(config.get(OUTPUT_FORMAT)))
        .failureMode(failureMode)
        .inputMode(InputMode.from(config.get(INPUT_MODE)))
        .routeByExitCode(routeByExitCode)
        .includeOutput(booleanOption(config, "includeOutput", true))
        .includeInput(booleanOption(config, "includeInput", false))
        .captureStderr(booleanOption(config, "captureStderr", false))
        .maxOutputLines(
            MapUtils.convert(
                config.getOrDefault("maxOutputLines", DEFAULT_MAX_OUTPUT_LINES), Integer.class))
        .maxOutputBytes(
            MapUtils.convert(
                config.getOrDefault("maxOutputBytes", DEFAULT_MAX_OUTPUT_BYTES), Long.class))
        .build();
  }

  private boolean booleanOption(
      final Map<String, Object> config, final String key, final boolean defaultValue) {
    return MapUtils.convert(config.getOrDefault(key, defaultValue), Boolean.class);
  }

  private Mono<String> resolveWorkingDir(
      final Map<String, Object> config, final Message<?> message) {
    return resolveValue(config.get("workingDir"), message).map(Object::toString).defaultIfEmpty("");
  }

  private Mono<Long> resolveTimeout(final Map<String, Object> config, final Message<?> message) {
    return resolveValue(config.getOrDefault(TIMEOUT, DEFAULT_TIMEOUT_SECONDS), message)
        .map(v -> MapUtils.convert(v, Long.class))
        .defaultIfEmpty(DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Whether the command runs through a shell. It must be written as true or false: a variable
   * expression could let message data switch the shell on.
   */
  private static boolean useShell(final Map<String, Object> config) {
    final Object raw = config.getOrDefault("useShell", false);
    if (raw instanceof final Boolean flag) {
      return flag;
    }
    if (raw instanceof final String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return Boolean.parseBoolean(text);
    }
    throw new IllegalArgumentException("useShell must be true or false, not an expression");
  }

  /**
   * Resolves the command elements. With a shell, the elements are one script; an element that uses
   * a variable is made one inert word ({@link ProcessExecutorGateway#quoteForShell}), so the
   * message cannot add shell syntax. Elements without a variable are the script as written.
   */
  private Mono<List<String>> resolveCommand(
      final List<Object> command, final Message<?> message, final boolean useShell) {
    if (command == null || command.isEmpty()) {
      log.atWarn().log("Command resolution failed: command is mandatory");
      return Mono.error(new IllegalArgumentException("command is mandatory"));
    }
    final String osName = System.getProperty("os.name");
    return Flux.fromIterable(command)
        .flatMapSequential(
            value ->
                resolveValue(value, message)
                    .map(Object::toString)
                    .map(
                        text ->
                            useShell && !resolver.isStatic(value)
                                ? ProcessExecutorGateway.quoteForShell(text, osName)
                                : text))
        .collectList();
  }

  private Mono<Map<String, String>> resolveEnv(
      final Map<String, Object> env, final Message<?> message) {
    if (env == null || env.isEmpty()) {
      return Mono.just(Map.of());
    }
    return Flux.fromIterable(env.entrySet())
        .flatMapSequential(
            entry ->
                resolveValue(entry.getValue(), message)
                    .map(val -> Map.entry(entry.getKey(), val.toString())))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .doOnNext(
            resolved ->
                log.atDebug().log(
                    "Process executor environment variables resolved: {} variable(s)",
                    resolved.size()));
  }

  private Mono<Object> resolveValue(final Object value, final Message<?> message) {
    if (value == null) {
      return Mono.empty();
    }
    return resolver.resolve(value, message);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get(COMMAND) == null) {
      log.atWarn().log("Process executor configuration validation failed: command is mandatory");
      return Mono.error(new IllegalArgumentException("command is mandatory"));
    }
    final Object timeout = config.get(TIMEOUT);
    if (timeout instanceof final Number number && number.longValue() <= 0) {
      log.atWarn().log("Process executor configuration validation failed: non-positive timeout");
      return Mono.error(new IllegalArgumentException("timeout must be positive, got: " + timeout));
    }
    try {
      ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY);
      useShell(config);
      OutputFormat.from(config.get(OUTPUT_FORMAT));
      InputMode.from(config.get(INPUT_MODE));
      final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
      if (booleanOption(config, ROUTE_BY_EXIT_CODE, false) && failureMode != FailureMode.CONTINUE) {
        throw new IllegalArgumentException("routeByExitCode requires failureMode: CONTINUE");
      }
    } catch (IllegalArgumentException e) {
      log.atWarn().log("Process executor configuration validation failed: {}", e.getMessage());
      return Mono.error(e);
    }
    return Mono.empty();
  }
}
