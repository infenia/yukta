// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.util.VariableResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentRequired",
  "PMD.TooManyStaticImports",
  "PMD.LinguisticNaming",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter",
  "PMD.UseConcurrentHashMap",
  "unchecked"
})
@NoArgsConstructor
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessExecutorPluginTest {

  private ProcessExecutorPlugin plugin;
  @Mock private ProcessExecutorGateway gateway;
  @Mock private VariableResolver resolver;

  @BeforeEach
  void setUp() {
    plugin = new ProcessExecutorPlugin(gateway, resolver);
    // Generic resolver mock to handle all variable resolutions by returning the input
    when(resolver.resolve(any(), any()))
        .thenAnswer(
            invocation -> {
              final Object arg = invocation.getArgument(0);
              return Mono.justOrEmpty(arg);
            });
  }

  private static ProcessExecutionResult successResult(final String... stdoutLines) {
    return ProcessExecutionResult.of(0, List.of(stdoutLines), null, 5L, false, false);
  }

  private static ProcessExecutionResult failedResult(final int exitCode, final String... stdout) {
    return ProcessExecutionResult.of(exitCode, List.of(stdout), null, 5L, false, false);
  }

  private static ProcessExecutionResult timedOutResult() {
    return ProcessExecutionResult.of(
        ProcessExecutionResult.TIMEOUT_EXIT_CODE, null, null, 1000L, true, false);
  }

  private void gatewayReturns(final ProcessExecutionResult result) {
    when(gateway.executeForResult(any())).thenReturn(Mono.just(result));
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  // --- plugin identity ---

  @Test
  void testGetType() {
    assertThat(plugin.getType()).isEqualTo("PROCESS_EXECUTOR");
  }

  @Test
  void testGetDescription() {
    assertThat(plugin.getDescription()).isNotEmpty();
  }

  @Test
  void getUsagePattern_documentsAllConfigurationKeys() {
    assertThat(plugin.getUsagePattern())
        .contains("REQUIRED")
        .contains("command")
        .contains("workingDir")
        .contains("timeout")
        .contains("outputFormat")
        .contains("failureMode")
        .contains("captureStderr")
        .contains("includeOutput")
        .contains("includeInput")
        .contains("maxOutputLines")
        .contains("maxOutputBytes")
        .contains("routeByExitCode");
  }

  @Test
  void testAutoConfiguration() {
    final ProcessExecutorAutoConfiguration autoConfig = new ProcessExecutorAutoConfiguration();
    assertThat(autoConfig.processExecutorPlugin(gateway, resolver)).isNotNull();
  }

  // --- structured output (default) ---

  @Test
  void process_success_emitsStructuredPayloadWithRealExitCode() {
    final Map<String, Object> config = Map.of("command", List.of("echo", "hello"));
    gatewayReturns(successResult("hello"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload)
                  .containsEntry("exitCode", 0)
                  .containsEntry("success", true)
                  .containsEntry("timedOut", false)
                  .containsEntry("durationMillis", 5L)
                  .containsEntry("outputTruncated", false)
                  .containsEntry("stdout", "hello")
                  .containsEntry("stderr", "")
                  .doesNotContainKey("input");
              assertThat(msg.getMetadata().get("exitCode")).isEqualTo(0);
            })
        .verifyComplete();
  }

  @Test
  void process_explicitStructuredFormatWithoutOutput_omitsStdoutKeys() {
    final Map<String, Object> config =
        Map.of("command", List.of("echo"), "outputFormat", "STRUCTURED", "includeOutput", false);
    gatewayReturns(successResult("hello"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload).doesNotContainKey("stdout").doesNotContainKey("stderr");
            })
        .verifyComplete();
  }

  @Test
  void process_includeInput_embedsOriginalPayload() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "includeInput", true);
    gatewayReturns(successResult("out"));

    StepVerifier.create(plugin.process(Flux.just(message("original-data")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload).containsEntry("input", "original-data");
            })
        .verifyComplete();
  }

  @Test
  void process_success_preservesOriginalMetadataAndAddsExitCode() {
    final Map<String, Object> originalMetadata = new HashMap<>();
    originalMetadata.put("traceId", "123");
    originalMetadata.put("sessionId", "456");
    final Message<?> input = message("payload").withMetadata(originalMetadata);
    gatewayReturns(successResult("hello"));

    StepVerifier.create(
            plugin.process(Flux.just(input), Map.of("command", List.of("echo", "hello"))))
        .assertNext(
            msg -> {
              assertThat(msg.getMetadata().get("exitCode")).isEqualTo(0);
              assertThat(msg.getMetadata().get("traceId")).isEqualTo("123");
              assertThat(msg.getMetadata().get("sessionId")).isEqualTo("456");
            })
        .verifyComplete();
  }

  // --- failure modes ---

  @Test
  void process_nonZeroExitWithDefaultErrorMode_failsWithExitCodeAndOutput() {
    final Map<String, Object> config = Map.of("command", List.of("false"));
    gatewayReturns(failedResult(3, "boom"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(WorkflowExecutionException.class);
              assertThat(error.getMessage()).contains("exit code 3").contains("boom");
            });
  }

  @Test
  void process_nonZeroExitWithCapturedStderr_errorMessageIncludesStderr() {
    final Map<String, Object> config =
        Map.of("command", List.of("false"), "failureMode", "ERROR", "captureStderr", true);
    when(gateway.executeForResult(any()))
        .thenReturn(
            Mono.just(
                ProcessExecutionResult.of(
                    2, List.of("out"), List.of("something went wrong"), 0L, false, false)));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .verifyErrorSatisfies(
            error ->
                assertThat(error.getMessage())
                    .contains("exit code 2")
                    .contains("--- Stderr ---")
                    .contains("something went wrong"));
  }

  @Test
  void process_timeoutWithErrorMode_failsWithTimeoutMessage() {
    final Map<String, Object> config = Map.of("command", List.of("sleep", "99"), "timeout", 1);
    gatewayReturns(timedOutResult());

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(WorkflowExecutionException.class);
              assertThat(error.getMessage()).contains("timed out after 1s");
            });
  }

  @Test
  void process_nonZeroExitWithContinueMode_emitsResultWithRealExitCode() {
    final Map<String, Object> config =
        Map.of("command", List.of("false"), "failureMode", "continue");
    gatewayReturns(failedResult(3, "boom"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload)
                  .containsEntry("exitCode", 3)
                  .containsEntry("success", false)
                  .containsEntry("stdout", "boom");
              assertThat(msg.getMetadata().get("exitCode")).isEqualTo(3);
            })
        .verifyComplete();
  }

  @Test
  void process_timeoutWithContinueMode_emitsTimedOutResult() {
    final Map<String, Object> config =
        Map.of("command", List.of("sleep", "99"), "failureMode", "CONTINUE");
    gatewayReturns(timedOutResult());

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload).containsEntry("timedOut", true).containsEntry("success", false);
              assertThat(msg.getMetadata().get("exitCode"))
                  .isEqualTo(ProcessExecutionResult.TIMEOUT_EXIT_CODE);
            })
        .verifyComplete();
  }

  // --- raw and passthrough formats ---

  @Test
  void process_rawFormat_payloadIsStdoutString() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "outputFormat", "RAW");
    gatewayReturns(successResult("line1", "line2"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(msg -> assertThat(msg.getPayload()).isEqualTo("line1\nline2"))
        .verifyComplete();
  }

  @Test
  void process_passthroughFormat_forwardsOriginalPayload() {
    final Map<String, Object> config =
        Map.of("command", List.of("echo"), "outputFormat", "passthrough");
    gatewayReturns(successResult("ignored"));

    StepVerifier.create(plugin.process(Flux.just(message("original")), config))
        .assertNext(
            msg -> {
              assertThat(msg.getPayload()).isEqualTo("original");
              assertThat(msg.getMetadata().get("exitCode")).isEqualTo(0);
            })
        .verifyComplete();
  }

  // --- json format ---

  @Test
  void process_jsonFormat_parsesStdoutIntoOutputField() {
    final Map<String, Object> config = Map.of("command", List.of("report"), "outputFormat", "JSON");
    gatewayReturns(successResult("{\"passed\": 10, \"failed\": 0}"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              final Map<String, Object> output = (Map<String, Object>) payload.get("output");
              assertThat(output).containsEntry("passed", 10).containsEntry("failed", 0);
              assertThat(payload).containsEntry("exitCode", 0);
            })
        .verifyComplete();
  }

  @Test
  void process_jsonFormatInvalidOutputWithErrorMode_failsWithParseError() {
    final Map<String, Object> config = Map.of("command", List.of("report"), "outputFormat", "json");
    gatewayReturns(successResult("not json at all"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(WorkflowExecutionException.class);
              assertThat(error.getMessage()).contains("parse process output as JSON");
            });
  }

  @Test
  void process_jsonFormatInvalidOutputWithContinueMode_setsParseError() {
    final Map<String, Object> config =
        Map.of(
            "command", List.of("report"),
            "outputFormat", "JSON",
            "failureMode", "CONTINUE");
    gatewayReturns(successResult("not json at all"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload.get("output")).isNull();
              assertThat(payload.get("parseError")).asString().isNotEmpty();
            })
        .verifyComplete();
  }

  @Test
  void process_jsonFormatFailedProcessWithContinueMode_skipsParsing() {
    final Map<String, Object> config =
        Map.of(
            "command", List.of("report"),
            "outputFormat", "JSON",
            "failureMode", "CONTINUE");
    gatewayReturns(failedResult(1, "not json"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              final Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
              assertThat(payload).containsKey("output").doesNotContainKey("parseError");
              assertThat(payload.get("output")).isNull();
              assertThat(payload).containsEntry("exitCode", 1);
            })
        .verifyComplete();
  }

  // --- config resolution and spec building ---

  @Test
  void process_missingCommand_errorsWithIllegalArgument() {
    StepVerifier.create(plugin.process(Flux.just(message("test")), Map.of()))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void process_emptyCommandList_errorsWithIllegalArgument() {
    StepVerifier.create(plugin.process(Flux.just(message("test")), Map.of("command", List.of())))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void process_zeroTimeout_errorsWithIllegalArgument() {
    final Map<String, Object> config = Map.of("command", List.of("sleep", "1"), "timeout", 0);

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void process_defaultExecutionOptions_buildsSpecWithDefaults() {
    final Map<String, Object> config = Map.of("command", List.of("echo", "hello"));
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture()))
        .thenReturn(Mono.just(successResult("hello")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .expectNextCount(1)
        .verifyComplete();

    final ProcessExecutionSpec spec = specCaptor.getValue();
    assertThat(spec.command()).containsExactly("echo", "hello");
    assertThat(spec.workingDir()).isNull();
    assertThat(spec.timeoutSeconds()).isEqualTo(300L);
    assertThat(spec.env()).isEmpty();
    assertThat(spec.useShell()).isFalse();
    assertThat(spec.captureStderr()).isFalse();
    assertThat(spec.maxOutputLines()).isEqualTo(10_000);
    assertThat(spec.maxOutputBytes()).isEqualTo(10L * 1024 * 1024);
  }

  @Test
  void process_allExecutionOptionsProvided_buildsCompleteSpec() {
    final Map<String, Object> config = new HashMap<>();
    config.put("command", List.of("sh", "-c", "echo hello"));
    config.put("workingDir", "/home");
    config.put("timeout", 120);
    config.put("env", Map.of("PATH", "/usr/bin"));
    config.put("useShell", true);
    config.put("captureStderr", true);
    config.put("maxOutputLines", 50);
    config.put("maxOutputBytes", 2048);
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture()))
        .thenReturn(Mono.just(successResult("hello")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .expectNextCount(1)
        .verifyComplete();

    final ProcessExecutionSpec spec = specCaptor.getValue();
    assertThat(spec.command()).containsExactly("sh", "-c", "echo hello");
    assertThat(spec.workingDir()).isEqualTo("/home");
    assertThat(spec.timeoutSeconds()).isEqualTo(120L);
    assertThat(spec.env()).containsEntry("PATH", "/usr/bin");
    assertThat(spec.useShell()).isTrue();
    assertThat(spec.captureStderr()).isTrue();
    assertThat(spec.maxOutputLines()).isEqualTo(50);
    assertThat(spec.maxOutputBytes()).isEqualTo(2048L);
  }

  @Test
  void process_emptyStringWorkingDir_specGetsNull() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "workingDir", "");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture()))
        .thenReturn(Mono.just(successResult("out")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().workingDir()).isNull();
  }

  @Test
  void process_nullWorkingDirValue_resolvesToDefault() {
    final Map<String, Object> config = new HashMap<>();
    config.put("command", List.of("echo"));
    config.put("workingDir", null);
    gatewayReturns(successResult("out"));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void process_envWithNullValues_filtersNullEntries() {
    final Map<String, Object> envWithNull = new HashMap<>();
    envWithNull.put("VAR1", "value1");
    envWithNull.put("VAR2", null);
    final Map<String, Object> config = Map.of("command", List.of("echo"), "env", envWithNull);
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture()))
        .thenReturn(Mono.just(successResult("out")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env())
        .containsEntry("VAR1", "value1")
        .doesNotContainKey("VAR2");
  }

  @Test
  void process_explicitNullEnv_resolvesToEmptyMap() {
    final Map<String, Object> config = new HashMap<>();
    config.put("command", List.of("echo"));
    config.put("env", null);
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture()))
        .thenReturn(Mono.just(successResult("out")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env()).isEmpty();
  }

  @Test
  void process_invalidOutputFormat_errorsWithIllegalArgument() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "outputFormat", "XML");

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(IllegalArgumentException.class);
              assertThat(error.getMessage()).contains("Unknown outputFormat");
            });
  }

  @Test
  void process_invalidFailureMode_errorsWithIllegalArgument() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "failureMode", "RETRY");

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(IllegalArgumentException.class);
              assertThat(error.getMessage()).contains("Unknown failureMode");
            });
  }

  // --- error propagation ---

  @Test
  void process_gatewayGenericError_propagates() {
    final Map<String, Object> config = Map.of("command", List.of("bad-command"));
    when(gateway.executeForResult(any()))
        .thenReturn(Mono.error(new RuntimeException("Generic execution error")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .verifyError(RuntimeException.class);
  }

  @Test
  void process_gatewayWorkflowExecutionException_propagatesWithDetails() {
    final Map<String, Object> config = Map.of("command", List.of("fail"));
    when(gateway.executeForResult(any()))
        .thenReturn(Mono.error(new WorkflowExecutionException("Workflow failed: exit code 5")));

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(WorkflowExecutionException.class);
              assertThat(error.getMessage()).contains("exit code");
            });
  }

  // --- stream behavior ---

  @Test
  void process_multipleMessagesInStream_processesSequentially() {
    final Map<String, Object> config =
        Map.of("command", List.of("echo", "hello"), "outputFormat", "PASSTHROUGH");
    gatewayReturns(successResult("hello"));

    StepVerifier.create(plugin.process(Flux.just(message("msg1"), message("msg2")), config))
        .assertNext(msg -> assertThat(msg.getPayload()).isEqualTo("msg1"))
        .assertNext(msg -> assertThat(msg.getPayload()).isEqualTo("msg2"))
        .verifyComplete();
  }

  // --- validateConfig ---

  @Test
  void validateConfig_missingCommand_errors() {
    StepVerifier.create(plugin.validateConfig(Map.of()))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void validateConfig_commandPresent_completes() {
    StepVerifier.create(plugin.validateConfig(Map.of("command", List.of("echo", "test"))))
        .verifyComplete();
  }

  @Test
  void validateConfig_nonPositiveNumericTimeout_errors() {
    StepVerifier.create(plugin.validateConfig(Map.of("command", List.of("echo"), "timeout", 0)))
        .verifyErrorSatisfies(
            error -> assertThat(error.getMessage()).contains("timeout must be positive"));
  }

  @Test
  void validateConfig_positiveNumericTimeout_completes() {
    StepVerifier.create(plugin.validateConfig(Map.of("command", List.of("echo"), "timeout", 60)))
        .verifyComplete();
  }

  @Test
  void validateConfig_expressionTimeout_skipsNumericValidation() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("command", List.of("echo"), "timeout", "${env.TIMEOUT}")))
        .verifyComplete();
  }

  @Test
  void validateConfig_invalidOutputFormat_errors() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("command", List.of("echo"), "outputFormat", "YAML")))
        .verifyErrorSatisfies(
            error -> assertThat(error.getMessage()).contains("Unknown outputFormat"));
  }

  @Test
  void validateConfig_invalidFailureMode_errors() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("command", List.of("echo"), "failureMode", "IGNORE")))
        .verifyErrorSatisfies(
            error -> assertThat(error.getMessage()).contains("Unknown failureMode"));
  }

  @Test
  void validateConfig_validFormatsAndModes_completes() {
    StepVerifier.create(
            plugin.validateConfig(
                Map.of(
                    "command", List.of("echo"),
                    "outputFormat", "json",
                    "failureMode", "continue",
                    "inputMode", "stdin")))
        .verifyComplete();
  }

  @Test
  void validateConfig_invalidInputMode_errors() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("command", List.of("echo"), "inputMode", "SOCKET")))
        .verifyErrorSatisfies(
            error -> assertThat(error.getMessage()).contains("Unknown inputMode"));
  }

  // --- message-aware variable resolution ---

  @Test
  void process_templatedCommand_resolvedAgainstInputMessage() {
    final Message<?> input = message(Map.of("version", "1.2.3"));
    when(resolver.resolve(eq("${payload.version}"), any())).thenReturn(Mono.just("1.2.3"));
    final Map<String, Object> config = Map.of("command", List.of("deploy", "${payload.version}"));
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().command()).containsExactly("deploy", "1.2.3");
    final ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(resolver, atLeastOnce()).resolve(any(), messageCaptor.capture());
    assertThat(messageCaptor.getAllValues()).contains(input);
  }

  // --- inputMode: ENV ---

  @Test
  void process_inputModeEnv_exportsMetadataAndPayload() {
    final Message<?> input =
        message("data-payload").withMetadata(Map.of("buildId", "42", "user.name", "jo"));
    final Map<String, Object> config = Map.of("command", List.of("echo"), "inputMode", "ENV");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env())
        .containsEntry("YUKTA_METADATA_BUILDID", "42")
        .containsEntry("YUKTA_METADATA_USER_NAME", "jo")
        .containsEntry("YUKTA_PAYLOAD", "data-payload");
  }

  @Test
  void process_inputModeEnvWithMapPayload_exportsPayloadAsJson() {
    final Message<?> input = message(Map.of("key", "value"));
    final Map<String, Object> config = Map.of("command", List.of("echo"), "inputMode", "env");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env()).containsEntry("YUKTA_PAYLOAD", "{\"key\":\"value\"}");
  }

  @Test
  void process_inputModeEnv_explicitEnvWinsOverExportedValues() {
    final Message<?> input = message("payload-value");
    final Map<String, Object> config =
        Map.of(
            "command", List.of("echo"),
            "inputMode", "ENV",
            "env", Map.of("YUKTA_PAYLOAD", "explicit-override"));
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env()).containsEntry("YUKTA_PAYLOAD", "explicit-override");
  }

  @Test
  void process_inputModeEnvWithNullPayloadAndNullMetadataValue_skipsThem() {
    // DefaultMessage forbids null metadata values, so use a mock to cover the defensive branches
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("present", "yes");
    metadata.put("absent", null);
    final Message<?> input = mock(Message.class);
    // First call (env export) sees the null value; later calls (output message construction)
    // get a null-free view since DefaultMessage rejects null metadata values
    when(input.getMetadata()).thenReturn(metadata, Map.of("present", "yes"));
    when(input.getPayload()).thenReturn(null);
    final Map<String, Object> config = Map.of("command", List.of("echo"), "inputMode", "ENV");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env())
        .containsEntry("YUKTA_METADATA_PRESENT", "yes")
        .doesNotContainKey("YUKTA_METADATA_ABSENT")
        .doesNotContainKey("YUKTA_PAYLOAD");
  }

  // --- inputMode: STDIN ---

  @Test
  void process_inputModeStdinWithStringPayload_writesPayloadAsIs() {
    final Message<?> input = message("line1\nline2");
    final Map<String, Object> config = Map.of("command", List.of("cat"), "inputMode", "STDIN");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().stdin()).isEqualTo("line1\nline2");
  }

  @Test
  void process_inputModeStdinWithMapPayload_writesPayloadAsJson() {
    final Message<?> input = message(Map.of("key", "value"));
    final Map<String, Object> config = Map.of("command", List.of("cat"), "inputMode", "stdin");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().stdin()).isEqualTo("{\"key\":\"value\"}");
  }

  @Test
  void process_inputModeStdinWithNullPayload_writesNothing() {
    final Message<?> input = message(null);
    final Map<String, Object> config = Map.of("command", List.of("cat"), "inputMode", "STDIN");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().stdin()).isNull();
  }

  @Test
  void process_inputModeNoneExplicit_doesNotTouchEnvOrStdin() {
    final Message<?> input = message("payload").withMetadata(Map.of("buildId", "42"));
    final Map<String, Object> config = Map.of("command", List.of("echo"), "inputMode", "NONE");
    final ArgumentCaptor<ProcessExecutionSpec> specCaptor =
        ArgumentCaptor.forClass(ProcessExecutionSpec.class);
    when(gateway.executeForResult(specCaptor.capture())).thenReturn(Mono.just(successResult("ok")));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(specCaptor.getValue().env()).isEmpty();
    assertThat(specCaptor.getValue().stdin()).isNull();
  }

  @Test
  void process_inputModeStdinWithUnserializablePayload_failsWithWorkflowException() {
    final Message<?> input = message(new Unserializable());
    final Map<String, Object> config = Map.of("command", List.of("cat"), "inputMode", "STDIN");
    gatewayReturns(successResult("ok"));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(WorkflowExecutionException.class);
              assertThat(error.getMessage()).contains("serialize input payload");
            });
  }

  @Test
  void process_invalidInputMode_errorsWithIllegalArgument() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "inputMode", "PIPE");

    StepVerifier.create(plugin.process(Flux.just(message("test")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(IllegalArgumentException.class);
              assertThat(error.getMessage()).contains("Unknown inputMode");
            });
  }

  // --- routeByExitCode: success/failure output ports ---

  @Test
  void getOutputPorts_defaultConfig_singleDefaultPort() {
    assertThat(plugin.getOutputPorts(Map.of("command", List.of("echo"))))
        .containsExactly("default");
  }

  @Test
  void getOutputPorts_routeByExitCode_successAndFailurePorts() {
    assertThat(plugin.getOutputPorts(Map.of("routeByExitCode", true)))
        .containsExactly("success", "failure");
  }

  @Test
  void process_routeByExitCodeSuccess_stampsSuccessPort() {
    final Map<String, Object> config =
        Map.of("command", List.of("echo"), "failureMode", "CONTINUE", "routeByExitCode", true);
    gatewayReturns(successResult("ok"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(msg -> assertThat(msg.getSourcePort()).isEqualTo("success"))
        .verifyComplete();
  }

  @Test
  void process_routeByExitCodeNonZeroExit_stampsFailurePort() {
    final Map<String, Object> config =
        Map.of("command", List.of("false"), "failureMode", "CONTINUE", "routeByExitCode", true);
    gatewayReturns(failedResult(3, "boom"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(
            msg -> {
              assertThat(msg.getSourcePort()).isEqualTo("failure");
              assertThat(msg.getMetadata().get("exitCode")).isEqualTo(3);
            })
        .verifyComplete();
  }

  @Test
  void process_routeByExitCodeTimeout_stampsFailurePort() {
    final Map<String, Object> config =
        Map.of(
            "command", List.of("sleep", "99"), "failureMode", "CONTINUE", "routeByExitCode", true);
    gatewayReturns(timedOutResult());

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(msg -> assertThat(msg.getSourcePort()).isEqualTo("failure"))
        .verifyComplete();
  }

  @Test
  void process_routeByExitCodeWithoutContinueMode_errorsWithIllegalArgument() {
    final Map<String, Object> config = Map.of("command", List.of("echo"), "routeByExitCode", true);
    gatewayReturns(successResult("ok"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .verifyErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(IllegalArgumentException.class);
              assertThat(error.getMessage()).contains("requires failureMode: CONTINUE");
            });
  }

  @Test
  void process_withoutRouting_leavesSourcePortUntouched() {
    final Map<String, Object> config = Map.of("command", List.of("echo"));
    gatewayReturns(successResult("ok"));

    StepVerifier.create(plugin.process(Flux.just(message("input")), config))
        .assertNext(msg -> assertThat(msg.getSourcePort()).isNull())
        .verifyComplete();
  }

  @Test
  void validateConfig_routeByExitCodeWithoutContinue_errors() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("command", List.of("echo"), "routeByExitCode", true)))
        .verifyErrorSatisfies(
            error -> assertThat(error.getMessage()).contains("requires failureMode: CONTINUE"));
  }

  @Test
  void validateConfig_routeByExitCodeWithContinue_completes() {
    StepVerifier.create(
            plugin.validateConfig(
                Map.of(
                    "command",
                    List.of("echo"),
                    "failureMode",
                    "CONTINUE",
                    "routeByExitCode",
                    true)))
        .verifyComplete();
  }

  /** Payload whose serialization deterministically fails. */
  @SuppressWarnings({"PMD.CommentDefaultAccessModifier", "PMD.PublicMemberInNonPublicType"})
  static final class Unserializable {
    public String getValue() {
      throw new IllegalStateException("boom");
    }
  }
}
