// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
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
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.GodClass"
})
class AgentSessionPluginTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The assistant line every fixture prints first. */
  private static final String WORKING =
      "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"working\"},"
          + "{\"type\":\"tool_use\",\"name\":\"Read\"}]}}";

  @TempDir private Path dir;

  /** Records what the plugin writes to its conversations. */
  private final RecordingGateway recording = new RecordingGateway();

  private AgentSessionPlugin plugin;

  /** The input request system the plugin sees; tests set it. */
  private final java.util.concurrent.atomic.AtomicReference<
          com.infenia.yukta.plugin.input.InputGateway>
      inputGateway = new java.util.concurrent.atomic.AtomicReference<>();

  /** The conversation gateway the plugin sees; none unless a test sets one. */
  private final java.util.concurrent.atomic.AtomicReference<
          com.infenia.yukta.plugin.agent.AgentConversationGateway>
      conversationGateway = new java.util.concurrent.atomic.AtomicReference<>();

  private final List<com.infenia.yukta.plugin.input.InputRequestSpec> asked =
      new CopyOnWriteArrayList<>();

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

  @SuppressWarnings("unchecked")
  private org.springframework.beans.factory.ObjectProvider<
          com.infenia.yukta.plugin.input.InputGateway>
      inputs() {
    final var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenAnswer(i -> inputGateway.get());
    return provider;
  }

  @SuppressWarnings("unchecked")
  private org.springframework.beans.factory.ObjectProvider<
          com.infenia.yukta.plugin.agent.AgentConversationGateway>
      conversations() {
    final var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenAnswer(i -> conversationGateway.get());
    return provider;
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
                + " \"{\\\"type\\\":\\\"result\\\",\\\"subtype\\\":\\\"success\\\","
                + "\\\"is_error\\\":false,\\\"result\\\":\\\"done:"
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
    Files.writeString(
        dir.resolve("asker.sh"),
        """
        read msg
        echo '{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"description":"list files"}}'
        read reply
        behavior=$(printf %s "$reply" | sed -n 's/.*"behavior":"\\([a-z]*\\)".*/\\1/p')
        message=$(printf %s "$reply" | sed -n 's/.*"message":"\\([^"]*\\)".*/\\1/p')
        echo "{\\"type\\":\\"result\\",\\"subtype\\":\\"success\\",\\"is_error\\":false,\\"result\\":\\"$behavior|$message\\",\\"session_id\\":\\"s\\"}"
        """);
    Files.writeString(
        dir.resolve("asker.json"),
        """
        {"id":"asker","description":"fixture that asks","command":["sh","-c","exit 9"],
         "capabilities":{"ask":true},
         "interactive":{"command":["sh","%s"],
           "userMessage":{"type":"user","message":{"role":"user","content":"{prompt}"}},
           "requestWhen":{"type":"control_request","request.subtype":"can_use_tool"},
           "requestId":"request_id","tool":"request.tool_name","input":"request.input","description":"request.description",
           "allow":{"type":"control_response","response":{"request_id":"{id}","response":{"behavior":"allow","updatedInput":"{input}"}}},
           "deny":{"type":"control_response","response":{"request_id":"{id}","response":{"behavior":"deny","message":"{message}"}}}},
         "output":{"resultWhen":{"type":"result"},"text":"result","sessionId":"session_id","isError":"is_error","subtype":"subtype"}}
        """
            .replace("%s", dir.resolve("asker.sh").toString()));
    Files.writeString(dir.resolve("crash.json"), preset("crash", "echo boom; exit 3"));
    final String echoArgs =
        "echo \"{\\\"type\\\":\\\"result\\\",\\\"subtype\\\":\\\"success\\\","
            + "\\\"is_error\\\":false,\\\"result\\\":\\\"$*\\\",\\\"session_id\\\":\\\"sess-1\\\"}\"";
    Files.writeString(dir.resolve("resumable.json"), resumable(preset("resumable", echoArgs)));
    Files.writeString(dir.resolve("slow.json"), resumable(preset("slow", "sleep 1; " + echoArgs)));
    Files.writeString(dir.resolve("rcrash.json"), resumable(preset("rcrash", "exit 3")));
    Files.writeString(
        dir.resolve("rmissing.json"),
        resumable(preset("rmissing", "x").replace("\"sh\"", "\"/no/such/program\"")));
    Files.writeString(
        dir.resolve("noisy.json"),
        preset(
            "noisy",
            "echo oops >&2; "
                + lines(
                    "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                        + "\"result\":\"ok\",\"session_id\":\"s4\"}")));
    Files.writeString(
        dir.resolve("modeled.json"),
        preset(
                "modeled",
                lines(
                    "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                        + "\"result\":\"ok\",\"session_id\":\"s5\"}"))
            .replace("\"modelArgs\":[]", "\"modelArgs\":[\"--model\",\"{model}\"]"));
    Files.writeString(
        dir.resolve("capable.json"),
        preset(
                "capable",
                lines(
                    "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                        + "\"result\":\"ok\",\"session_id\":\"s7\"}"))
            .replace("\"stream\":true", "\"stream\":true,\"resume\":true"));
    Files.writeString(
        dir.resolve("mute.json"),
        preset(
                "mute",
                lines(
                    "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                        + "\"result\":\"ok\",\"session_id\":\"s8\"}"))
            .replace("\"stream\":true", "\"stream\":true,\"ask\":true"));
    Files.writeString(
        dir.resolve("acpfresh.json"),
        "{\"id\":\"acpfresh\",\"protocol\":\"acp\",\"command\":[\"x\"]}");
    Files.writeString(
        dir.resolve("pty.json"),
        preset("pty", "x").replace("\"id\":\"pty\"", "\"id\":\"pty\",\"protocol\":\"pty\""));
    Files.writeString(
        dir.resolve("thinker.json"),
        preset(
                "thinker",
                lines(
                    "{\"type\":\"result\",\"subtype\":\"success\","
                        + "\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s6\"}"))
            .replace(
                "\"live\":[",
                "\"live\":[{\"when\":{\"type\":\"assistant\"},\"text\":\"message.content[*].text\","
                    + "\"kind\":\"thought\"},"));
    final VariableResolver resolver = mock(VariableResolver.class);
    when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(i -> Mono.just(i.getArgument(0)));
    final ProcessExecutorGateway gateway = new ProcessExecutorGateway();
    org.springframework.test.util.ReflectionTestUtils.setField(gateway, "allowAny", true);
    try {
      plugin =
          new AgentSessionPlugin(
              gateway,
              resolver,
              new AgentHarnesses(JSON, dir.toString()),
              new InMemoryAgentSessionStore(),
              inputs(),
              conversations(),
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
            .blockOptional(Duration.ofSeconds(30))
            .orElseThrow();
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
  void errorResult_goesToFailureWithTheReason() {
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
  void noResultLine_failsNamingTheExitCode() {
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
            "unknown harness 'nope'; one of [acpfresh, antigravity, asker, capable, claude-code,"
                + " codex, crash, failed, max, modeled, mute, noisy, ok, pty, rcrash, resumable,"
                + " rmissing, slow, terminal, thinker]")
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
        .containsExactly(
            "acpfresh",
            "antigravity",
            "asker",
            "capable",
            "claude-code",
            "codex",
            "crash",
            "failed",
            "max",
            "modeled",
            "mute",
            "noisy",
            "ok",
            "pty",
            "rcrash",
            "resumable",
            "rmissing",
            "slow",
            "terminal",
            "thinker");
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

  private Message<?> runIn(final String harness, final String session, final String onBusy) {
    return plugin
        .process(
            Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
            Map.of("harness", harness, "prompt", "go", "session", session, "onBusy", onBusy))
        .collectList()
        .blockOptional(Duration.ofSeconds(30))
        .orElseThrow()
        .getFirst();
  }

  @Test
  void sessionKey_resumesFromTheTokenTheLastTurnLeft() {
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
  void busySession_failsAtOnceWhenOnBusyIsFail() throws Exception {
    final var running =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> runIn("slow", "k", "WAIT"));
    org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(300)).until(() -> true);

    final Message<?> refused = runIn("slow", "k", "FAIL");

    assertThat(refused.sourcePort()).isEqualTo("failure");
    assertThat(payload(refused)).containsEntry("reason", "session 'k' is busy");
    assertThat(running.get().sourcePort()).isEqualTo("success");
  }

  @Test
  void busySession_queuesTheNextTurnWhenOnBusyIsWait() throws Exception {
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
  void validateConfig_refusesSessionOnHarnessThatCannotResume() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "ok", "prompt", "p", "session", "k")))
        .expectErrorMessage("harness 'ok' cannot resume a session; remove session")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "resumable", "prompt", "p", "onBusy", "NOW")))
        .expectErrorMessage("onBusy must be WAIT or FAIL, got: NOW")
        .verify();
  }

  private Message<?> runAsking(
      final com.infenia.yukta.plugin.input.InputGateway gateway, final Map<String, Object> extra) {
    inputGateway.set(gateway);
    final Map<String, Object> config = new java.util.concurrent.ConcurrentHashMap<>(extra);
    config.putAll(Map.of("harness", "asker", "prompt", "list", "approvals", "ASK"));
    return plugin
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")), config)
        .contextWrite(
            Context.of(
                "sessionId", "s-1", "workflowId", "w-1", "executionId", "e-1", "nodeId", "n-1"))
        .collectList()
        .blockOptional(Duration.ofSeconds(30))
        .orElseThrow()
        .getFirst();
  }

  private com.infenia.yukta.plugin.input.InputGateway answering(
      final String decision, final String why) {
    return spec -> {
      asked.add(spec);
      return Mono.just(
          new com.infenia.yukta.plugin.input.InputResponse(
              "req-1",
              decision,
              null,
              Map.of(),
              com.infenia.yukta.plugin.input.Responder.SYSTEM,
              why,
              null,
              java.time.Instant.now()));
    };
  }

  @Test
  void approvals_anApprovedToolUseIsAllowedAndAsksThroughTheInputSystem() {
    final Message<?> out = runAsking(answering("approve", null), Map.of("approvalRisk", "HIGH"));

    assertThat(out.sourcePort()).isEqualTo("success");
    assertThat(payload(out)).containsEntry("text", "allow|");
    assertThat(asked).hasSize(1);
    final var spec = asked.getFirst();
    assertThat(spec.kind()).isEqualTo(com.infenia.yukta.plugin.input.InputKind.APPROVAL);
    assertThat(spec.risk()).isEqualTo(com.infenia.yukta.plugin.input.InputRisk.HIGH);
    assertThat(spec.prompt()).contains("asker", "Bash", "list files");
    assertThat(spec.context()).containsEntry("tool", "Bash");
    assertThat(spec.context().get("input").toString()).contains("ls");
  }

  @Test
  void approvals_rejectionIsRefusedWithTheReason() {
    final Message<?> out = runAsking(answering("reject", "too risky"), Map.of());

    assertThat(payload(out)).containsEntry("text", "deny|Rejected: too risky");
  }

  @Test
  void approvals_noAnswerOrNoInputSystemRefusesTheTool() {
    final Message<?> failing =
        runAsking(spec -> Mono.error(new IllegalStateException("nobody answered")), Map.of());
    final Message<?> none = runAsking(null, Map.of());

    assertThat(payload(failing)).containsEntry("text", "deny|Not approved: nobody answered");
    assertThat(payload(none).get("text").toString()).startsWith("deny|Not approved");
  }

  @Test
  void validateConfig_approvalsNeedHarnessThatCanAsk() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "ok", "prompt", "p", "approvals", "ASK")))
        .expectErrorMessage("harness 'ok' cannot ask for approvals; remove approvals")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "asker", "prompt", "p", "approvals", "MAYBE")))
        .expectErrorMessage("approvals must be NONE or ASK, got: MAYBE")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "asker", "prompt", "p", "approvals", "ASK")))
        .verifyComplete();
  }

  @Test
  void theCodexPreset_cannotTakeMaxTurnsSessionsOrApprovals() {
    // the shipped preset declares only what it was written to do
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "codex", "prompt", "p", "maxTurns", 3)))
        .expectErrorMessage("harness 'codex' has no turn limit; remove maxTurns")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "codex", "prompt", "p", "session", "k")))
        .expectErrorMessage("harness 'codex' cannot resume a session; remove session")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "codex", "prompt", "p", "approvals", "ASK")))
        .expectErrorMessage("harness 'codex' cannot ask for approvals; remove approvals")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "codex", "prompt", "p", "model", "gpt-5")))
        .verifyComplete();
  }

  @Test
  void tierAndRestriction() {
    assertThat(plugin.getTier()).isEqualTo(com.infenia.yukta.plugin.core.PluginTier.EXTENDED);
    assertThat(plugin.isRestricted()).isEqualTo(true);
  }

  @Test
  void identityPortsAndTheStepTimeoutFollowTheProcessTimeout() {
    assertThat(plugin.getType()).isEqualTo("AGENT_SESSION");
    assertThat(plugin.getOutputPorts()).containsExactly("success", "failure", "incomplete");
    assertThat(plugin.getDefaultTimeout(Map.of())).isEqualTo(Duration.ofSeconds(660));
    assertThat(plugin.getDefaultTimeout(Map.of("timeout", 10))).isEqualTo(Duration.ofSeconds(40));
  }

  @Test
  void workingDirectoryAndEnvironmentAreAccepted() {
    for (final Map<String, String> env :
        List.of(Map.of("FIXTURE", "1"), Map.<String, String>of())) {
      final List<Message<?>> out =
          plugin
              .process(
                  Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
                  Map.of("harness", "ok", "prompt", "p", "workingDir", dir.toString(), "env", env))
              .collectList()
              .blockOptional(Duration.ofSeconds(30))
              .orElseThrow();

      assertThat(out.getFirst().sourcePort()).isEqualTo("success");
    }
  }

  @Test
  void failedTurnDoesNotAdvanceTheSession() {
    final Message<?> first = runIn("rcrash", "f", "WAIT");
    final Message<?> second = runIn("rcrash", "f", "WAIT");

    assertThat(first.sourcePort()).isEqualTo("failure");
    assertThat(payload(first)).containsEntry("turn", 1);
    assertThat(payload(second)).containsEntry("turn", 1);
  }

  @Test
  void turnThatErrorsFreesTheSession() {
    final var config = Map.<String, Object>of("harness", "rmissing", "prompt", "p", "session", "e");

    StepVerifier.create(
            plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")), config))
        .expectError()
        .verify(Duration.ofSeconds(30));
    final Message<?> next = runIn("resumable", "e", "FAIL");

    assertThat(next.sourcePort()).isEqualTo("success");
  }

  @Test
  void cancelledTurnFreesTheSession() throws InterruptedException {
    final reactor.core.Disposable running =
        plugin
            .process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
                Map.of("harness", "slow", "prompt", "p", "session", "c"))
            .subscribe();
    org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(300)).until(() -> true);
    running.dispose();

    final Message<?> next = runIn("resumable", "c", "FAIL");

    assertThat(next.sourcePort()).isEqualTo("success");
    assertThat(payload(next)).containsEntry("turn", 1);
  }

  // --- conversations ---

  private Message<?> runInNode(
      final String harness, final Map<String, Object> extra, final List<String> log) {
    final Map<String, Object> config = new java.util.concurrent.ConcurrentHashMap<>(extra);
    config.putAll(Map.of("harness", harness, "prompt", "fix it"));
    final PluginLogSink sink = (stream, line, level) -> log.add(stream + ":" + line);
    return plugin
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")), config)
        .contextWrite(
            Context.of(
                "sessionId",
                "s-1",
                "workflowId",
                "w-1",
                "executionId",
                "e-1",
                "nodeId",
                "n-1",
                WorkflowContext.LOG_SINK_KEY,
                sink))
        .collectList()
        .blockOptional(Duration.ofSeconds(30))
        .orElseThrow()
        .getFirst();
  }

  private Message<?> runRecorded(
      final String harness, final Map<String, Object> extra, final List<String> log) {
    conversationGateway.set(recording);
    return runInNode(harness, extra, log);
  }

  @Test
  void runWritesItsConversationInOrderAndTheRunLogIsTheSameAsBefore() {
    final List<String> log = new CopyOnWriteArrayList<>();

    final Message<?> out = runRecorded("ok", Map.of(), log);

    assertThat(recording.calls)
        .containsExactly(
            "emit MESSAGE prompt {role=user, text=fix it, author=prompt, more=false}",
            "state RUNNING null",
            "emit MESSAGE m1 {role=agent, text=working, more=false}",
            "emit TOOL t2 {title=Read, status=running}",
            "emit TOOL t2 {status=completed}",
            "emit RESULT null {status=success, text=done: fix it, turn=1}",
            "end success null");
    assertThat(log).containsExactly("agent:working", "agent:tool: Read");
    assertThat(payload(out))
        .containsEntry("conversationId", "c-1")
        .containsEntry("status", "success");
    final var spec = recording.specs.getFirst();
    assertThat(spec.harness()).isEqualTo("ok");
    assertThat(spec.protocol()).isEqualTo(com.infenia.yukta.plugin.agent.AgentProtocol.JSONL);
    assertThat(spec.who().executionId()).isEqualTo("e-1");
    assertThat(spec.who().nodeId()).isEqualTo("n-1");
    assertThat(spec.model()).isNull();
    assertThat(spec.capabilities()).isEmpty();
    assertThat(spec.steerAudience().selectors()).containsExactly("role:OPERATOR");
  }

  @Test
  void failedTurnEndsTheConversationAsFailureAndFailsItsTools() {
    runRecorded("failed", Map.of(), new CopyOnWriteArrayList<>());

    assertThat(recording.calls)
        .contains(
            "emit TOOL t2 {status=failed}",
            "emit RESULT null {status=failure, reason=Not logged in, text=Not logged in, turn=1}",
            "end failure Not logged in");
  }

  @Test
  void turnThatStoppedEarlyEndsTheConversationAsIncomplete() {
    runRecorded("max", Map.of(), new CopyOnWriteArrayList<>());

    assertThat(recording.calls.getLast()).isEqualTo("end incomplete error_max_turns");
  }

  @Test
  void standardErrorBecomesTheNoticeAndTheRunLogIsUnchanged() {
    final List<String> log = new CopyOnWriteArrayList<>();

    runRecorded("noisy", Map.of(), log);

    assertThat(recording.calls).contains("emit NOTICE null {level=WARN, text=oops}");
    // the run log is unchanged: standard error was merged into standard output before
    assertThat(log).contains("stdout:oops");
  }

  @Test
  void ruleOfKindThoughtWritesTheThoughtAndTheModelIsRecorded() {
    runRecorded("thinker", Map.of(), new CopyOnWriteArrayList<>());
    recording.calls.clear();
    recording.specs.clear();
    runRecorded("modeled", Map.of("model", "opus"), new CopyOnWriteArrayList<>());

    assertThat(recording.specs.getFirst().model()).isEqualTo("opus");
  }

  @Test
  void thoughtRuleBecomesTheMessageWithTheRoleThought() {
    runRecorded("thinker", Map.of(), new CopyOnWriteArrayList<>());

    assertThat(recording.calls)
        .contains("emit MESSAGE m1 {role=thought, text=working, more=false}");
  }

  @Test
  void harnessThatCanResumeAndAskSaysSo() {
    runRecorded("capable", Map.of(), new CopyOnWriteArrayList<>());

    assertThat(recording.specs.getFirst().capabilities()).containsExactly("resume");
  }

  @Test
  void processThatCannotStartEndsTheConversationAsFailureAndTheNodeFails() {
    conversationGateway.set(recording);

    StepVerifier.create(
            plugin
                .process(
                    Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
                    Map.of("harness", "rmissing", "prompt", "p"))
                .contextWrite(
                    Context.of(
                        "sessionId",
                        "s-1",
                        "workflowId",
                        "w-1",
                        "executionId",
                        "e-1",
                        "nodeId",
                        "n-1")))
        .expectError()
        .verify(Duration.ofSeconds(30));

    assertThat(recording.calls.getLast()).startsWith("end failure ");
  }

  @Test
  void cancelledRunEndsTheConversationAsIncomplete() {
    conversationGateway.set(recording);
    final reactor.core.Disposable running =
        plugin
            .process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")),
                Map.of("harness", "slow", "prompt", "p"))
            .contextWrite(
                Context.of(
                    "sessionId", "s-1", "workflowId", "w-1", "executionId", "e-1", "nodeId", "n-1"))
            .subscribe();
    org.awaitility.Awaitility.await().until(() -> !recording.calls.isEmpty());
    running.dispose();

    org.awaitility.Awaitility.await()
        .until(() -> recording.calls.contains("end incomplete cancelled"));
    assertThat(recording.calls).contains("state RUNNING null", "end incomplete cancelled");
  }

  @Test
  void approvalIsTheRequestEventAndTheConversationWaitsWhileItIsOpen() {
    conversationGateway.set(recording);

    runAsking(answering("approve", null), Map.of());

    assertThat(recording.calls)
        .contains("state WAITING approval", "state RUNNING null", "end success null");
    assertThat(recording.calls.stream().filter(c -> c.startsWith("emit REQUEST r1 ")))
        .hasSize(2)
        .satisfiesExactly(
            opened ->
                assertThat(opened)
                    .contains("inputKind=APPROVAL", "state=open", "tool=Bash")
                    .contains("list files"),
            answered ->
                assertThat(answered)
                    .contains(
                        "inputRequestId=req-1",
                        "state=answered",
                        "decision=approve",
                        "by=system:system"));
    assertThat(recording.specs.getFirst().capabilities()).contains("ask");
  }

  @Test
  void expiredOrCancelledOrFailedApprovalIsEndedInTheRequestEvent() {
    conversationGateway.set(recording);
    runAsking(
        spec ->
            Mono.error(new com.infenia.yukta.plugin.input.InputExpiredException("req-9", "late")),
        Map.of());
    runAsking(
        spec ->
            Mono.error(new com.infenia.yukta.plugin.input.InputCancelledException("req-9", "gone")),
        Map.of());
    runAsking(spec -> Mono.error(new IllegalStateException("no input system")), Map.of());

    assertThat(recording.calls)
        .contains(
            "emit REQUEST r1 {state=expired, reason=InputExpiredException}",
            "emit REQUEST r1 {state=cancelled, reason=InputCancelledException}",
            "emit REQUEST r1 {state=cancelled, reason=no input system}");
  }

  @Test
  void withoutTheGatewayOrAnIdentityOrWhenOpeningFailsThereIsNoConversationAndTheRunGoesOn() {
    final Message<?> noGateway = run("ok", new CopyOnWriteArrayList<>());
    conversationGateway.set(recording);
    final Message<?> noIdentity = run("ok", new CopyOnWriteArrayList<>());
    conversationGateway.set(spec -> Mono.error(new IllegalStateException("store down")));
    final Message<?> failing = runInNode("ok", Map.of(), new CopyOnWriteArrayList<>());
    conversationGateway.set(null);

    assertThat(payload(noGateway))
        .doesNotContainKey("conversationId")
        .containsEntry("status", "success");
    assertThat(payload(noIdentity)).doesNotContainKey("conversationId");
    assertThat(payload(failing))
        .doesNotContainKey("conversationId")
        .containsEntry("status", "success");
    assertThat(recording.specs).isEmpty();
  }

  @Test
  void anAcpHarnessThatDoesNotDeclareResumeRefusesSessionKey() {
    StepVerifier.create(
            plugin.validateConfig(Map.of("harness", "acpfresh", "prompt", "p", "session", "k")))
        .expectErrorMessage("harness 'acpfresh' cannot resume a session; remove session")
        .verify();
  }

  @Test
  void terminalHarnessWithoutItsPtyBlockIsRefusedWhenTheNodeIsChecked() {
    StepVerifier.create(plugin.validateConfig(Map.of("harness", "pty", "prompt", "p")))
        .expectErrorMessage("harness 'pty' has no 'pty' block; remove harness")
        .verify();
  }

  @Test
  void askingForApprovalsOfTheHarnessWithoutTheConversationBlockClaimsNoAskCapability() {
    runRecorded("mute", Map.of("approvals", "ASK"), new CopyOnWriteArrayList<>());

    assertThat(recording.specs.getFirst().capabilities()).isEmpty();
  }

  @Test
  void nodeWithoutApprovalsOrConversationGatewayRunsTheOneShotCommand() {
    final Message<?> out = runInNode("asker", Map.of(), new CopyOnWriteArrayList<>());

    assertThat(out.sourcePort()).isEqualTo("failure");
    assertThat(payload(out)).containsEntry("exitCode", 9);
  }

  /** Records what the plugin writes to a conversation, as readable lines. */
  private static final class RecordingGateway
      implements com.infenia.yukta.plugin.agent.AgentConversationGateway {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<com.infenia.yukta.plugin.agent.AgentConversationSpec> specs =
        new CopyOnWriteArrayList<>();

    @Override
    public Mono<com.infenia.yukta.plugin.agent.AgentConversationHandle> open(
        final com.infenia.yukta.plugin.agent.AgentConversationSpec spec) {
      specs.add(spec);
      return Mono.just(new RecordingHandle(calls));
    }
  }

  /** The handle of {@link RecordingGateway}. */
  private static final class RecordingHandle
      implements com.infenia.yukta.plugin.agent.AgentConversationHandle {

    private final List<String> calls;

    private RecordingHandle(final List<String> calls) {
      this.calls = calls;
    }

    @Override
    @SuppressWarnings("PMD.ShortMethodName")
    public String id() {
      return "c-1";
    }

    @Override
    public void emit(
        final com.infenia.yukta.plugin.agent.AgentEventKind kind,
        final String itemId,
        final Map<String, Object> data) {
      calls.add("emit " + kind + " " + itemId + " " + data);
    }

    @Override
    public void state(final com.infenia.yukta.plugin.agent.AgentState state, final String reason) {
      calls.add("state " + state + " " + reason);
    }

    @Override
    public void queued(final int count) {
      calls.add("queued " + count);
    }

    @Override
    public Flux<com.infenia.yukta.plugin.agent.AgentCommand> commands() {
      return Flux.never();
    }

    @Override
    public void end(final String outcome, final String reason) {
      calls.add("end " + outcome + " " + reason);
    }
  }
}
