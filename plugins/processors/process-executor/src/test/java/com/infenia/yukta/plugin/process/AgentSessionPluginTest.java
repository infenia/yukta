// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs AGENT_SESSION against fixture harnesses: small presets whose command is a shell one-liner
 * that speaks the same JSON lines as the real harness, so no vendor CLI is needed.
 */
@NoArgsConstructor
@DisabledOnOs(OS.WINDOWS)
class AgentSessionPluginTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The assistant line every fixture prints first. */
  private static final String WORKING =
      "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"working\"},"
          + "{\"type\":\"tool_use\",\"name\":\"Read\"}]}}";

  @TempDir private Path dir;

  private AgentSessionPlugin plugin;

  private static String preset(final String id, final String script) {
    return "{\"id\":\""
        + id
        + "\",\"description\":\"fixture\",\"command\":[\"sh\",\"-c\",\""
        + script.replace("\\", "\\\\").replace("\"", "\\\"")
        + "\",\"fixture\",\"{prompt}\"],\"modelArgs\":[],\"capabilities\":{\"stream\":true},"
        + "\"output\":{\"resultWhen\":{\"type\":\"result\"},\"text\":\"result\","
        + "\"sessionId\":\"session_id\",\"isError\":\"is_error\",\"subtype\":\"subtype\","
        + "\"incompleteSubtypes\":[\"error_max_turns\"],\"live\":["
        + "{\"when\":{\"type\":\"assistant\"},\"text\":\"message.content[*].text\"},"
        + "{\"when\":{\"type\":\"assistant\"},\"text\":\"message.content[*].name\","
        + "\"prefix\":\"tool: \"}]}}";
  }

  /** Gives a fixture preset the resume arguments. */
  private static String resumable(final String preset) {
    return preset.replace(
        "\"modelArgs\":[]", "\"modelArgs\":[],\"resumeArgs\":[\"--resume\",\"{session}\"]");
  }

  private static String lines(final String result) {
    return "echo '" + WORKING + "'; echo '" + result + "'";
  }

  @BeforeEach
  void setUp() throws IOException {
    Files.writeString(
        dir.resolve("ok.json"),
        preset(
            "ok",
            "echo '"
                + WORKING
                + "'; echo"
                + " \"{\\\"type\\\":\\\"result\\\",\\\"subtype\\\":\\\"success\\\",\\\"is_error\\\":false,\\\"result\\\":\\\"done:"
                + " $1\\\",\\\"session_id\\\":\\\"s1\\\"}\""));
    Files.writeString(
        dir.resolve("failed.json"),
        preset(
            "failed",
            lines(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                    + "\"result\":\"Not logged in\",\"session_id\":\"s2\"}")));
    Files.writeString(
        dir.resolve("max.json"),
        preset(
            "max",
            lines(
                "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,"
                    + "\"result\":\"\",\"session_id\":\"s3\"}")));
    Files.writeString(dir.resolve("crash.json"), preset("crash", "echo boom; exit 3"));
    final String echoArgs =
        "echo \"{\\\"type\\\":\\\"result\\\",\\\"subtype\\\":\\\"success\\\","
            + "\\\"is_error\\\":false,\\\"result\\\":\\\"$*\\\",\\\"session_id\\\":\\\"sess-1\\\"}\"";
    Files.writeString(dir.resolve("resumable.json"), resumable(preset("resumable", echoArgs)));
    Files.writeString(dir.resolve("slow.json"), resumable(preset("slow", "sleep 1; " + echoArgs)));
    final VariableResolver resolver = mock(VariableResolver.class);
    when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(i -> Mono.just(i.getArgument(0)));
    final ProcessExecutorGateway gateway = new ProcessExecutorGateway();
    gateway.setAllowAny(true);
    try {
      plugin =
          new AgentSessionPlugin(
              gateway,
              resolver,
              new AgentHarnesses(JSON, dir.toString()),
              new InMemoryAgentSessionStore(),
              JSON);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Message<?> run(final String harness, final List<String> log) {
    final PluginLogSink sink = (stream, line, level) -> log.add(stream + ":" + line);
    final List<Message<?>> out =
        plugin
            .process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
                Map.of("harness", harness, "prompt", "fix it"))
            .contextWrite(Context.of(WorkflowContext.LOG_SINK_KEY, sink))
            .collectList()
            .block(Duration.ofSeconds(30));
    assertThat(out).hasSize(1);
    return out.getFirst();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payload(final Message<?> message) {
    return (Map<String, Object>) message.payload();
  }

  @Test
  void success_emitsTextAndSessionAndShowsTheWorkLive() {
    final List<String> log = new CopyOnWriteArrayList<>();

    final Message<?> out = run("ok", log);

    assertThat(out.sourcePort()).isEqualTo("success");
    assertThat(payload(out))
        .containsEntry("status", "success")
        .containsEntry("text", "done: fix it")
        .containsEntry("sessionId", "s1")
        .containsEntry("turn", 1);
    assertThat(log).containsExactly("agent:working", "agent:tool: Read");
  }

  @Test
  void anErrorResult_goesToFailureWithTheReason() {
    final Message<?> out = run("failed", new CopyOnWriteArrayList<>());

    assertThat(out.sourcePort()).isEqualTo("failure");
    assertThat(payload(out))
        .containsEntry("reason", "Not logged in")
        .containsEntry("sessionId", "s2");
  }

  @Test
  void stoppingEarly_goesToIncomplete() {
    final Message<?> out = run("max", new CopyOnWriteArrayList<>());

    assertThat(out.sourcePort()).isEqualTo("incomplete");
    assertThat(payload(out)).containsEntry("reason", "error_max_turns");
  }

  @Test
  void noResultLine_isAFailureNamingTheExitCode() {
    final List<String> log = new CopyOnWriteArrayList<>();

    final Message<?> out = run("crash", log);

    assertThat(out.sourcePort()).isEqualTo("failure");
    assertThat(payload(out).get("reason").toString()).contains("no result", "exit code 3");
    assertThat(log).contains("stdout:boom");
  }

  @Test
  void validateConfig_namesTheKnownHarnessesAndRefusesWhatItCannotDo() {
    StepVerifier.create(plugin.validateConfig(Map.of("harness", "nope", "prompt", "p")))
        .expectErrorMessage(
            "unknown harness 'nope'; one of [claude-code, crash, failed, max, ok, resumable, slow]")
        .verify();
    StepVerifier.create(plugin.validateConfig(Map.of("harness", "ok", "prompt", "p", "model", "m")))
        .expectErrorMessage("harness 'ok' does not take a model; remove model")
        .verify();
    StepVerifier.create(plugin.validateConfig(Map.of("harness", "ok", "prompt", "p")))
        .verifyComplete();
  }

  @Test
  void theConfigSchemaListsTheHarnessesAsOptions() {
    assertThat(plugin.getConfigSchema().getFirst().options())
        .containsExactly("claude-code", "crash", "failed", "max", "ok", "resumable", "slow");
    assertThat(plugin.describe().usagePattern()).contains("ok: fixture");
  }

  @Test
  void theShippedClaudeCodePresetLoads() throws IOException {
    final AgentPreset claude = new AgentHarnesses(JSON, "").get("claude-code");

    assertThat(claude.command()).containsSequence("claude", "-p", "{prompt}");
    assertThat(claude.capabilities()).contains("stream", "resume");
    assertThat(claude.maxTurnsArgs()).containsExactly("--max-turns", "{maxTurns}");
    assertThat(claude.output().incompleteSubtypes()).containsExactly("error_max_turns");
  }

  @Test
  void linesRecordedFromTheRealClaudeCode_areRead() throws IOException {
    final AgentPreset claude = new AgentHarnesses(JSON, "").get("claude-code");
    final List<String> log = new CopyOnWriteArrayList<>();
    final AgentOutput output =
        new AgentOutput(claude.output(), (stream, line, level) -> log.add(line), JSON);

    // recorded from `claude -p ... --output-format stream-json --verbose` (2.1.322)
    output.line("stdout", "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"108aa031\"}");
    output.line(
        "stdout",
        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
            + "\"session_id\":\"108aa031\"}");
    output.line(
        "stdout",
        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"hi\","
            + "\"session_id\":\"108aa031\"}");

    assertThat(log).containsExactly("hi");
    assertThat(output.outcome(0, false, 600))
        .containsEntry("status", "success")
        .containsEntry("text", "hi")
        .containsEntry("sessionId", "108aa031");
  }

  private Message<?> runIn(final String harness, final String session, final String onBusy) {
    return plugin
        .process(
            Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
            Map.of("harness", harness, "prompt", "go", "session", session, "onBusy", onBusy))
        .collectList()
        .block(Duration.ofSeconds(30))
        .getFirst();
  }

  @Test
  void aSessionKey_resumesFromTheTokenTheLastTurnLeft() {
    final Message<?> first = runIn("resumable", "k1", "WAIT");
    final Message<?> second = runIn("resumable", "k1", "WAIT");
    final Message<?> other = runIn("resumable", "k2", "WAIT");

    assertThat(payload(first)).containsEntry("turn", 1).containsEntry("text", "go");
    assertThat(payload(second))
        .containsEntry("turn", 2)
        .containsEntry("text", "go --resume sess-1");
    assertThat(payload(other)).containsEntry("turn", 1).containsEntry("text", "go");
  }

  @Test
  void aBusySession_failsAtOnceWhenOnBusyIsFail() throws Exception {
    final var running =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> runIn("slow", "k", "WAIT"));
    org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(300)).until(() -> true);

    final Message<?> refused = runIn("slow", "k", "FAIL");

    assertThat(refused.sourcePort()).isEqualTo("failure");
    assertThat(payload(refused)).containsEntry("reason", "session 'k' is busy");
    assertThat(running.get().sourcePort()).isEqualTo("success");
  }

  @Test
  void aBusySession_queuesTheNextTurnWhenOnBusyIsWait() throws Exception {
    final var running =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> runIn("slow", "w", "WAIT"));
    org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(300)).until(() -> true);

    final Message<?> queued = runIn("slow", "w", "WAIT");

    assertThat(running.get().sourcePort()).isEqualTo("success");
    assertThat(payload(queued))
        .containsEntry("turn", 2)
        .containsEntry("text", "go --resume sess-1");
  }

  @Test
  void validateConfig_refusesASessionOnAHarnessThatCannotResume() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "ok", "prompt", "p", "session", "k")))
        .expectErrorMessage("harness 'ok' cannot resume a session; remove session")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "resumable", "prompt", "p", "onBusy", "NOW")))
        .expectErrorMessage("onBusy must be WAIT or FAIL, got: NOW")
        .verify();
  }
}
