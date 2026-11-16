// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.process.ProcessExecutionResult;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Agent Client Protocol adapter against a fake agent: the two sessions recorded from the real
 * Antigravity are replayed frame by frame, and scripted agents cover what the recordings do not.
 */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.UseConcurrentHashMap",
  "PMD.TooManyStaticImports",
  "PMD.CouplingBetweenObjects"
})
class AcpAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final AgentRequests.Settings ASK =
      new AgentRequests.Settings(InputRisk.LOW, Duration.ofSeconds(5), Audience.CURRENT_TIER);

  private static final String PROMPT = "session/prompt";

  private static final String PRESET_JSON =
      """
      {"id":"fake","protocol":"acp","command":["fake","acp"],"capabilities":{"ask":true,"resume":true}}
      """;

  private final List<Long> promptIds = new CopyOnWriteArrayList<>();

  /** What Yukta wrote to the agent. */
  private final List<JsonNode> written = new CopyOnWriteArrayList<>();

  /** What the conversation was told. */
  private final Sinks.Many<AgentCommand> commands = Sinks.many().unicast().onBackpressureBuffer();

  private final RecordingConversation conversation = new RecordingConversation(commands.asFlux());

  private final Sinks.One<ProcessExecutionResult> exit = Sinks.one();

  private final List<String> logged = new CopyOnWriteArrayList<>();
  private final PluginLogSink log = (stream, line, level) -> logged.add(stream + " " + line);

  /** The agent: what it says to each line Yukta writes. */
  private Function<JsonNode, List<String>> agent = frame -> List.of();

  private final java.util.concurrent.atomic.AtomicReference<PluginLogSink> sink =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** The agent does not exit when its standard input is closed. */
  private final java.util.concurrent.atomic.AtomicBoolean stubborn =
      new java.util.concurrent.atomic.AtomicBoolean();

  private Duration beat = Duration.ofSeconds(30);

  private Duration idleTimeout = Duration.ofSeconds(30);

  private final ProcessExecutorGateway gateway = mock(ProcessExecutorGateway.class);

  private InputGateway answers = spec -> Mono.error(new IllegalStateException("no input"));

  private final ObjectProvider<InputGateway> inputs = mock(ObjectProvider.class);

  private AcpAdapter adapter() {
    when(inputs.getIfAvailable()).thenAnswer(call -> answers);
    when(gateway.executeInteractive(any(), any(), any()))
        .thenAnswer(call -> {
          sink.set(call.getArgument(1));
          final java.util.function.Consumer<ProcessExecutorGateway.StdinWriter> start =
              call.getArgument(2);
          return Mono.defer(() -> {
            start.accept(
                new ProcessExecutorGateway.StdinWriter() {
                  @Override
                  public void write(final String line) {
                    final JsonNode frame = JSON.readTree(line);
                    written.add(frame);
                    agent.apply(frame).forEach(out -> sink.get().line("stdout", out, null));
                  }

                  @Override
                  public void close() {
                    closed.set(true);
                    if (!stubborn.get()) {
                      exit.tryEmitValue(
                          new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
                    }
                  }
                });
            return exit.asMono();
          });
        });
    return new AcpAdapter(
        gateway, inputs, JSON, beat, Duration.ofMillis(20), Duration.ofMillis(150));
  }

  private static AgentPreset preset(final String json) {
    return AgentPreset.parse(JSON.readTree(json));
  }

  private AgentRun run(
      final AgentPreset preset,
      final String prompt,
      final boolean chat,
      final AgentRequests.Settings approvals,
      final String resume,
      final long timeout) {
    return new AgentRun(
        DefaultMessage.create(UUID.randomUUID(), "in"),
        preset,
        ProcessExecutionSpec.builder().command(List.of("fake")).workingDir("/work").build(),
        prompt,
        null,
        approvals,
        timeout,
        conversation,
        log,
        new InputIdentity("s", "w", "e", "n"),
        chat,
        idleTimeout,
        () -> {},
        resume);
  }

  private CompletableFuture<Map<String, Object>> start(final AgentRun run) {
    return adapter().run(run).toFuture();
  }

  private Map<String, Object> done(final CompletableFuture<Map<String, Object>> result)
      throws java.util.concurrent.ExecutionException,
          InterruptedException,
          java.util.concurrent.TimeoutException {
    return result.get(5, TimeUnit.SECONDS);
  }

  // ---- scripted agents

  private static String line(final Map<String, Object> frame) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("jsonrpc", "2.0");
    out.putAll(frame);
    return JSON.writeValueAsString(out);
  }

  private static String answer(final JsonNode request, final Object result) {
    return line(Map.of("id", request.path("id").asLong(), "result", result));
  }

  private static String failure(final JsonNode request, final int code, final String message) {
    return line(
        Map.of(
            "id", request.path("id").asLong(), "error", Map.of("code", code, "message", message)));
  }

  private static String update(final Map<String, Object> update) {
    return line(
        Map.of("method", "session/update", "params", Map.of("sessionId", "sid", "update", update)));
  }

  private static String chunk(final String kind, final String text) {
    return update(Map.of("sessionUpdate", kind, "content", Map.of("type", "text", "text", text)));
  }

  private static String permission(final Object id, final Object toolCall, final List<?> options) {
    return line(
        Map.of(
            "id",
            id,
            "method",
            "session/request_permission",
            "params",
            Map.of("sessionId", "sid", "toolCall", toolCall, "options", options)));
  }

  private static Map<String, Object> option(final String id, final String kind) {
    return Map.of("optionId", id, "name", id, "kind", kind);
  }

  /** Answers the handshake and ends each prompt with the given text and stop reason. */
  private Function<JsonNode, List<String>> agentThatSays(final String text, final String stop) {
    return frame -> {
      final String method = frame.path("method").asString("");
      return switch (method) {
        case "initialize" ->
            List.of(
                answer(
                    frame,
                    Map.of(
                        "protocolVersion", 1, "agentCapabilities", Map.of("loadSession", true))));
        case "session/new" -> List.of(answer(frame, Map.of("sessionId", "sid")));
        case "session/prompt" ->
            List.of(chunk("agent_message_chunk", text), answer(frame, Map.of("stopReason", stop)));
        default -> List.of();
      };
    };
  }

  private Function<JsonNode, List<String>> agentThat(
      final String method, final Function<JsonNode, List<String>> special) {
    final Function<JsonNode, List<String>> base = agentThatSays("hello", "end_turn");
    return frame ->
        method.equals(frame.path("method").asString("")) ? special.apply(frame) : base.apply(frame);
  }

  private List<String> methods() {
    return written.stream().map(f -> f.path("method").asString("")).toList();
  }

  // ---- the recordings

  private static List<JsonNode> recording(final String name) throws IOException {
    final List<JsonNode> frames = new ArrayList<>();
    try (var in =
        AcpAdapterTest.class.getResourceAsStream("/agent-sessions/antigravity-acp/" + name)) {
      for (final String line :
          new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        frames.add(JSON.readTree(line));
      }
    }
    return frames;
  }

  /**
   * Plays a recording: each frame Yukta writes must be the next recorded one, then the agent talks.
   */
  private Function<JsonNode, List<String>> replaying(final List<JsonNode> frames) {
    final int[] next = {0};
    return frame -> {
      final JsonNode expected = frames.get(next[0]);
      assertThat(expected.path("dir").asString()).isEqualTo("in");
      assertThat(frame).isEqualTo(expected.path("frame"));
      next[0]++;
      final List<String> out = new ArrayList<>();
      while (next[0] < frames.size() && "out".equals(frames.get(next[0]).path("dir").asString())) {
        // the agent's lines are written by the caller; the permission answer arrives inside
        out.add(frames.get(next[0]).path("frame").toString());
        next[0]++;
        if (out.getLast().contains("session/request_permission")) {
          break;
        }
      }
      return out;
    };
  }

  @Test
  void recordedPromptAndCancelReplaysIntoTheConversation() throws Exception {
    final List<JsonNode> frames = recording("prompt-and-cancel.jsonl");
    agent = replaying(frames);
    final String first =
        frames.get(4).path("frame").path("params").path("prompt").get(0).path("text").asString();
    final String second =
        frames
            .get(frames.size() - 4)
            .path("frame")
            .path("params")
            .path("prompt")
            .get(0)
            .path("text")
            .asString();

    final var result = start(run(preset(PRESET_JSON), first, true, null, null, 60));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().contains("state IDLE null"));
    commands.tryEmitNext(new AgentCommand.Message(second, false, "key:ann"));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> methods().stream().filter("session/prompt"::equals).count() == 2);
    commands.tryEmitNext(new AgentCommand.Interrupt("key:ann"));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/cancel"));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() ->
            conversation.calls().stream().filter(c -> c.startsWith("emit RESULT")).count() == 2);
    commands.tryEmitNext(new AgentCommand.End(null, null, "key:ann"));
    final Map<String, Object> outcome = done(result);

    assertThat(outcome)
        .containsEntry("status", "incomplete")
        .containsEntry("reason", "interrupted")
        .containsEntry("sessionId", "00000000-0000-4000-8000-000000000001")
        .containsEntry("turn", 2);
    assertThat(String.join("\n", conversation.calls()))
        .contains("emit MESSAGE m1 {role=thought")
        .contains("emit TOOL t2 {title=File hello.txt, kind=edit, input={Content=hi")
        .contains("status=completed")
        .contains("{role=agent, text=Created [hello.txt]")
        .contains("end incomplete interrupted");
    assertThat(closed.get()).isTrue();
  }

  @Test
  void recordedPermissionIsAnsweredWithTheApproveOptionOfTheAgent() throws Exception {
    final List<JsonNode> frames = recording("permission-and-cancel.jsonl");
    agent = replaying(frames);
    answers =
        spec ->
            Mono.just(
                new InputResponse(
                    "req", "approve", null, Map.of(), Responder.SYSTEM, null, null, Instant.now()));
    final String prompt =
        frames.get(4).path("frame").path("params").path("prompt").get(0).path("text").asString();

    final var result = start(run(preset(PRESET_JSON), prompt, false, ASK, null, 60));
    final Map<String, Object> outcome = done(result);

    assertThat(outcome).containsEntry("status", "success").containsEntry("turn", 1);
    assertThat(written.stream().filter(f -> f.has("result")).findFirst().orElseThrow())
        .isEqualTo(
            JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"outcome\":"
                    + "{\"outcome\":\"selected\",\"optionId\":\"allow\"}}}"));
    assertThat(String.join("\n", conversation.calls()))
        .contains("emit REQUEST call_14345140342120175555 {inputKind=APPROVAL")
        .contains("state WAITING approval");
  }

  // ---- the handshake

  @Test
  void taskRunsTheHandshakeThenThePromptAndClosesStandardInput() throws Exception {
    agent = agentThatSays("hello", "end_turn");

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(outcome)
        .containsEntry("status", "success")
        .containsEntry("text", "hello")
        .containsEntry("sessionId", "sid")
        .containsEntry("turn", 1)
        .containsEntry("exitCode", 0)
        .doesNotContainKey("reason");
    assertThat(methods()).containsExactly("initialize", "session/new", "session/prompt");
    assertThat(written.get(0).path("params"))
        .isEqualTo(
            JSON.readTree(
                "{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":"
                    + "{\"readTextFile\":false,\"writeTextFile\":false},\"terminal\":false}}"));
    assertThat(written.get(1).path("params"))
        .isEqualTo(JSON.readTree("{\"cwd\":\"/work\",\"mcpServers\":[]}"));
    assertThat(written.get(2).path("params").path("prompt").get(0).path("text").asString())
        .isEqualTo("go");
    assertThat(conversation.calls()).contains("end success null");
  }

  @Test
  void runWithoutDirectoryStartsTheSessionInTheCurrentOne() throws Exception {
    agent = agentThatSays("hello", "end_turn");
    final AgentRun base = run(preset(PRESET_JSON), "go", false, null, null, 60);
    final AgentRun here =
        new AgentRun(
            base.message(),
            base.preset(),
            ProcessExecutionSpec.builder().command(List.of("fake")).build(),
            base.prompt(),
            null,
            null,
            60,
            conversation,
            log,
            base.who(),
            false,
            base.idleTimeout(),
            base.heartbeat(),
            null);

    done(start(here));

    assertThat(written.get(1).path("params").path("cwd").asString())
        .isEqualTo(java.nio.file.Path.of("").toAbsolutePath().toString());
  }

  @Test
  void versionOfTheProtocolYuktaDoesNotSpeakFailsTheNodeWithReason() throws Exception {
    agent = agentThat("initialize", frame -> List.of(answer(frame, Map.of("protocolVersion", 2))));

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(outcome)
        .containsEntry("status", "failure")
        .containsEntry(
            "reason",
            "the agent speaks version 2 of the Agent Client Protocol; Yukta speaks version 1");
    assertThat(methods()).containsExactly("initialize");
    assertThat(closed.get()).isTrue();
  }

  @Test
  void presetWithAnAuthMethodAuthenticatesBeforeTheSession() throws Exception {
    agent = agentThat("authenticate", frame -> List.of(answer(frame, Map.of())));
    final AgentPreset preset =
        preset(
            "{\"id\":\"fake\",\"protocol\":\"acp\",\"command\":[\"fake\"],"
                + "\"acp\":{\"authMethod\":\"oauth-personal\"}}");

    done(start(run(preset, "go", false, null, null, 60)));

    assertThat(methods())
        .containsExactly("initialize", "authenticate", "session/new", "session/prompt");
    assertThat(written.get(1).path("params").path("methodId").asString())
        .isEqualTo("oauth-personal");
  }

  @Test
  void sessionTheAgentRefusesFailsTheNodeWithItsMessage() throws Exception {
    agent = agentThat("session/new", frame -> List.of(failure(frame, -32_000, "not logged in")));

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(outcome).containsEntry("status", "failure").containsEntry("reason", "not logged in");
  }

  @Test
  void sessionWithoutAnIdFailsTheNode() throws Exception {
    agent = agentThat("session/new", frame -> List.of(answer(frame, Map.of())));

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(outcome)
        .containsEntry("status", "failure")
        .containsEntry("reason", "the agent gave no session id");
  }

  @Test
  void resumeTokenLoadsTheSessionAndDropsTheHistoryItReplays() throws Exception {
    agent =
        agentThat(
            "session/load",
            frame -> List.of(chunk("agent_message_chunk", "OLD"), answer(frame, Map.of())));

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, "tok", 60)));

    assertThat(methods()).containsExactly("initialize", "session/load", "session/prompt");
    assertThat(written.get(1).path("params").path("sessionId").asString()).isEqualTo("tok");
    assertThat(outcome).containsEntry("text", "hello").containsEntry("sessionId", "tok");
  }

  @Test
  void resumeTokenStartsNewSessionWhenTheAgentCannotLoadOne() throws Exception {
    agent = agentThat("initialize", frame -> List.of(answer(frame, Map.of("protocolVersion", 1))));

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, "tok", 60)));

    assertThat(methods()).containsExactly("initialize", "session/new", "session/prompt");
    assertThat(outcome).containsEntry("sessionId", "sid");
  }

  @Test
  void blankResumeTokenStartsNewSession() throws Exception {
    agent = agentThatSays("hello", "end_turn");

    done(start(run(preset(PRESET_JSON), "go", false, null, " ", 60)));

    assertThat(methods()).contains("session/new").doesNotContain("session/load");
  }

  // ---- how a turn ends

  @Test
  void stopReasonsBecomeTheStatusOfTheNode() throws Exception {
    agent = agentThatSays("cut", "max_tokens");
    assertThat(done(start(run(preset(PRESET_JSON), "go", false, null, null, 60))))
        .containsEntry("status", "incomplete")
        .containsEntry("reason", "max_tokens")
        .containsEntry("text", "cut");
  }

  @Test
  void refusalFailsTheNode() throws Exception {
    agent = agentThatSays("no", "refusal");
    assertThat(done(start(run(preset(PRESET_JSON), "go", false, null, null, 60))))
        .containsEntry("status", "failure")
        .containsEntry("reason", "the agent refused");
  }

  @Test
  void promptTheAgentAnswersWithAnErrorFailsTheNode() throws Exception {
    agent = agentThat("session/prompt", frame -> List.of(failure(frame, -32_000, "quota")));

    assertThat(done(start(run(preset(PRESET_JSON), "go", false, null, null, 60))))
        .containsEntry("status", "failure")
        .containsEntry("reason", "quota");
  }

  @Test
  void anAgentThatExitsDuringTurnFailsTheNodeAndTheTurnIsRecordedAsFailed() throws Exception {
    agent = agentThat("session/prompt", frame -> List.of());
    final var result = start(run(preset(PRESET_JSON), "go", false, null, null, 60));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/prompt"));

    exit.tryEmitValue(new ProcessExecutionResult(3, List.of(), List.of(), 1, false, false));

    assertThat(done(result))
        .containsEntry("status", "failure")
        .containsEntry("reason", "the agent gave no result (exit code 3)")
        .containsEntry("exitCode", 3);
    assertThat(String.join("\n", conversation.calls()))
        .contains("emit RESULT null {status=failure");
  }

  @Test
  void processStoppedAtItsTimeoutFailsTheNode() throws Exception {
    agent = agentThat("session/prompt", frame -> List.of());
    final var result = start(run(preset(PRESET_JSON), "go", false, null, null, 7));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/prompt"));

    exit.tryEmitValue(new ProcessExecutionResult(-1, List.of(), List.of(), 1, true, false));

    assertThat(done(result))
        .containsEntry("status", "failure")
        .containsEntry("reason", "timed out after 7s");
  }

  @Test
  void processThatCannotStartFailsTheConversation() {
    when(inputs.getIfAvailable()).thenReturn(null);
    when(gateway.executeInteractive(any(), any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("no such command")));

    final var run = run(preset(PRESET_JSON), "go", false, null, null, 60);
    new AcpAdapter(gateway, inputs, JSON, beat, Duration.ofMillis(20), Duration.ofMillis(150))
        .run(run)
        .subscribe(ok -> {}, error -> {});

    assertThat(conversation.calls()).contains("end failure no such command");
  }

  @Test
  void cancelledRunEndsTheConversationIncomplete() {
    agent = agentThat("session/prompt", frame -> List.of());
    final var result =
        adapter().run(run(preset(PRESET_JSON), "go", false, null, null, 60)).subscribe();
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/prompt"));

    result.dispose();

    assertThat(conversation.calls()).contains("end incomplete cancelled");
  }

  @Test
  void theProtocolIsAcp() {
    assertThat(adapter().protocol()).isEqualTo(AgentProtocol.ACP);
  }

  // ---- what the agent reports

  @Test
  void whatTheAgentSaysAndDoesBecomesEventsOfTheConversation() throws Exception {
    agent =
        agentThat(
            "session/prompt",
            frame ->
                List.of(
                    "starting up",
                    chunk("agent_thought_chunk", "think"),
                    chunk("agent_thought_chunk", " more"),
                    chunk("agent_message_chunk", "Hel"),
                    chunk("agent_message_chunk", "lo"),
                    chunk("agent_message_chunk", ""),
                    update(Map.of("sessionUpdate", "usage_update", "used", 1)),
                    update(
                        Map.of(
                            "sessionUpdate",
                            "tool_call",
                            "toolCallId",
                            "c1",
                            "title",
                            "Run ls",
                            "kind",
                            "execute",
                            "status",
                            "in_progress",
                            "rawInput",
                            Map.of("a", 1))),
                    update(
                        Map.of(
                            "sessionUpdate",
                            "tool_call_update",
                            "toolCallId",
                            "c1",
                            "status",
                            "in_progress")),
                    update(
                        Map.of(
                            "sessionUpdate",
                            "tool_call_update",
                            "toolCallId",
                            "c1",
                            "status",
                            "completed",
                            "rawOutput",
                            Map.of("b", 2))),
                    update(Map.of("sessionUpdate", "tool_call_update", "toolCallId", "c2")),
                    update(
                        Map.of(
                            "sessionUpdate",
                            "tool_call_update",
                            "toolCallId",
                            "c2",
                            "status",
                            "failed")),
                    line(Map.of("method", "$/cancel_request", "params", Map.of("requestId", 9))),
                    update(
                        Map.of(
                            "sessionUpdate",
                            "plan",
                            "entries",
                            List.of(
                                Map.of("content", "x", "priority", "high", "status", "pending")))),
                    chunk("agent_message_chunk", "bye"),
                    answer(frame, Map.of("stopReason", "end_turn"))));

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    final String calls = String.join("\n", conversation.calls());
    assertThat(calls)
        .contains("emit MESSAGE m1 {role=thought, text=think more, more=false}")
        .contains("emit MESSAGE m2 {role=agent, text=Hello, more=false}")
        .contains("emit TOOL t3 {title=Run ls, kind=execute, input={a=1}, status=running}")
        .contains("emit TOOL t3 {status=running}")
        .contains("emit TOOL t3 {output={b=2}, status=completed}")
        .contains("emit TOOL t4 {status=running}")
        .contains("emit TOOL t4 {status=failed}")
        .contains("emit PLAN null {entries=[{")
        .contains("emit MESSAGE m5 {role=agent, text=bye, more=false}");
    assertThat(outcome).containsEntry("text", "Hellobye");
    assertThat(logged)
        .contains("stdout starting up", "agent think more", "agent Hello", "agent Run ls");
  }

  @Test
  void toolThatNeverEndsInFailedTurnIsClosedAsFailed() throws Exception {
    agent =
        agentThat(
            "session/prompt",
            frame ->
                List.of(
                    update(Map.of("sessionUpdate", "tool_call", "toolCallId", "c1", "title", "x")),
                    answer(frame, Map.of("stopReason", "refusal"))));

    done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(conversation.calls()).contains("emit TOOL t1 {status=failed}");
  }

  @Test
  void toolThatNeverEndsInSuccessfulTurnIsClosedAsCompleted() throws Exception {
    agent =
        agentThat(
            "session/prompt",
            frame ->
                List.of(
                    update(Map.of("sessionUpdate", "tool_call", "toolCallId", "c1", "title", "x")),
                    answer(frame, Map.of("stopReason", "end_turn"))));

    done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(conversation.calls()).contains("emit TOOL t1 {status=completed}");
  }

  @Test
  void longTextIsCutToSixtyFourKilobytesForThePayload() throws Exception {
    agent = agentThatSays("x".repeat(70_000), "end_turn");

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(String.valueOf(outcome.get("text"))).hasSize(65_536);
  }

  @Test
  void standardErrorBecomesNoticesAndStaysInTheRunLog() throws Exception {
    agent = agentThat("initialize", frame -> List.of(answer(frame, Map.of("protocolVersion", 1))));
    final var result = start(run(preset(PRESET_JSON), "go", false, null, null, 60));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> sink.get() != null && methods().contains("session/prompt"));

    sink.get().line("stderr", "warming", null);
    sink.get().line("stderr", "boom", com.infenia.yukta.plugin.core.LogLevel.ERROR);
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);

    assertThat(conversation.calls())
        .contains(
            "emit NOTICE null {level=WARN, text=warming}",
            "emit NOTICE null {level=ERROR, text=boom}");
    assertThat(logged).contains("stdout warming", "stdout boom");
  }

  @Test
  void textTheAgentTypesIsGatheredIntoOnePieceWhenTheTurnIsSlow() {
    agent = agentThat("session/prompt", frame -> List.of(chunk("agent_message_chunk", "slow")));

    start(run(preset(PRESET_JSON), "go", false, null, null, 60));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() ->
            conversation.calls().contains("emit MESSAGE m1 {role=agent, text=slow, more=false}"));
    assertThat(conversation.calls()).doesNotContain("end success null");
  }

  // ---- requests of the agent

  @Test
  void requestYuktaDoesNotServeIsAnsweredWithMethodNotFound() throws Exception {
    agent =
        agentThat(
            "session/prompt",
            frame ->
                List.of(
                    line(Map.of("id", 5, "method", "fs/read_text_file", "params", Map.of())),
                    answer(frame, Map.of("stopReason", "end_turn"))));

    done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(written.stream().filter(f -> f.has("error")).findFirst().orElseThrow())
        .isEqualTo(
            JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"error\":{\"code\":-32601,\"message\":\"Method not"
                    + " found: fs/read_text_file\"}}"));
  }

  private void askThen(final Object id, final Object toolCall, final List<?> options) {
    agent =
        agentThat(
            "session/prompt",
            frame ->
                List.of(
                    permission(id, toolCall, options),
                    answer(frame, Map.of("stopReason", "end_turn"))));
  }

  private JsonNode permissionAnswer() {
    return written.stream()
        .filter(f -> f.has("result") && f.path("result").has("outcome"))
        .findFirst()
        .orElseThrow();
  }

  private static InputGateway deciding(final String decision) {
    return spec ->
        Mono.just(
            new InputResponse(
                "req", decision, null, Map.of(), Responder.SYSTEM, null, null, Instant.now()));
  }

  @Test
  void withApprovalsOffPermissionIsRefusedWithTheRejectOption() throws Exception {
    askThen(
        "1",
        Map.of("toolCallId", "c1", "title", "rm", "kind", "execute"),
        List.of(option("allow", "allow_once"), option("deny", "reject_once")));

    done(start(run(preset(PRESET_JSON), "go", false, null, null, 60)));

    assertThat(permissionAnswer().path("result").path("outcome"))
        .isEqualTo(JSON.readTree("{\"outcome\":\"selected\",\"optionId\":\"deny\"}"));
    assertThat(permissionAnswer().path("id").asString()).isEqualTo("1");
  }

  @Test
  void anApprovalFallsBackToAlwaysAllowAndRejectionToAlwaysReject() throws Exception {
    askThen(
        7,
        Map.of("toolCallId", "c1"),
        List.of(option("a", "allow_always"), option("r", "reject_always")));
    answers = deciding("approve");
    done(start(run(preset(PRESET_JSON), "go", false, ASK, null, 60)));
    assertThat(permissionAnswer().path("result").path("outcome").path("optionId").asString())
        .isEqualTo("a");
    assertThat(permissionAnswer().path("id").asLong()).isEqualTo(7);
  }

  @Test
  void rejectionByPersonPicksTheRejectOption() throws Exception {
    askThen(
        "1",
        Map.of("toolCallId", "c1", "kind", "edit"),
        List.of(option("a", "allow_once"), option("r", "reject_always")));
    answers = deciding("reject");

    done(start(run(preset(PRESET_JSON), "go", false, ASK, null, 60)));

    assertThat(permissionAnswer().path("result").path("outcome").path("optionId").asString())
        .isEqualTo("r");
  }

  @Test
  void whenNoOptionMatchesTheDecisionTheRequestIsCancelled() throws Exception {
    askThen("1", Map.of("title", "x"), List.of(option("r", "reject_once")));
    answers = deciding("approve");

    done(start(run(preset(PRESET_JSON), "go", false, ASK, null, 60)));

    assertThat(permissionAnswer().path("result").path("outcome"))
        .isEqualTo(JSON.readTree("{\"outcome\":\"cancelled\"}"));
    assertThat(String.join("\n", conversation.calls()))
        .contains("{inputKind=APPROVAL, prompt=Agent (fake) wants to use x");
  }

  @Test
  void anInterruptAnswersTheOpenPermissionCancelledAndCancelsTheTurn() throws Exception {
    final long[] prompt = {0};
    agent =
        agentThat(
            "session/prompt",
            frame -> {
              prompt[0] = frame.path("id").asLong();
              return List.of(
                  permission("1", Map.of("toolCallId", "c1"), List.of(option("a", "allow_once"))));
            });
    answers = spec -> Mono.never();
    final Function<JsonNode, List<String>> inner = agent;
    agent =
        frame ->
            "session/cancel".equals(frame.path("method").asString(""))
                ? List.of(
                    line(Map.of("id", prompt[0], "result", Map.of("stopReason", "cancelled"))))
                : inner.apply(frame);
    final var result = start(run(preset(PRESET_JSON), "go", false, ASK, null, 60));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().contains("state WAITING approval"));

    commands.tryEmitNext(new AgentCommand.Interrupt("key:ann"));

    assertThat(done(result))
        .containsEntry("status", "incomplete")
        .containsEntry("reason", "interrupted");
    assertThat(permissionAnswer().path("result").path("outcome"))
        .isEqualTo(JSON.readTree("{\"outcome\":\"cancelled\"}"));
    assertThat(methods()).contains("session/cancel");
  }

  // ---- messages, interrupts and the end

  /** An agent that takes a prompt and answers only when the test says so. */
  private void holding() {
    final Function<JsonNode, List<String>> base = agentThatSays("done", "end_turn");
    agent =
        frame -> {
          if (PROMPT.equals(frame.path("method").asString(""))) {
            promptIds.add(frame.path("id").asLong());
            return List.of();
          }
          return base.apply(frame);
        };
  }

  private void finishPrompt(final int n, final String stop) {
    sink.get()
        .line(
            "stdout",
            line(Map.of("id", promptIds.get(n), "result", Map.of("stopReason", stop))),
            null);
  }

  private void awaitPrompts(final int n) {
    await().atMost(5, TimeUnit.SECONDS).until(() -> promptIds.size() == n);
  }

  @Test
  void messageDuringTurnWaitsForTheTurnToEnd() throws Exception {
    holding();
    final var result = start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    awaitPrompts(1);

    commands.tryEmitNext(new AgentCommand.Message("next", false, "key:ann"));
    await().atMost(5, TimeUnit.SECONDS).until(() -> conversation.calls().contains("queued 1"));
    assertThat(methods().stream().filter("session/prompt"::equals).count()).isEqualTo(1);
    finishPrompt(0, "end_turn");
    awaitPrompts(2);

    assertThat(written.getLast().path("params").path("prompt").get(0).path("text").asString())
        .isEqualTo("next");
    assertThat(String.join("\n", conversation.calls()))
        .contains("emit MESSAGE u1 {role=user, text=next, author=key:ann, more=false, queued=true}")
        .contains("queued 0");
    finishPrompt(1, "end_turn");
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().contains("state IDLE null"));
    commands.tryEmitNext(new AgentCommand.End("failure", "stop it", "key:ann"));
    assertThat(done(result))
        .containsEntry("status", "failure")
        .containsEntry("reason", "stop it")
        .containsEntry("turn", 2);
  }

  @Test
  void messageThatInterruptsCancelsTheTurnAndGoesNext() throws Exception {
    holding();
    start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    awaitPrompts(1);

    commands.tryEmitNext(new AgentCommand.Message("now", true, "key:ann"));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/cancel"));
    finishPrompt(0, "cancelled");
    awaitPrompts(2);

    assertThat(written.getLast().path("params").path("prompt").get(0).path("text").asString())
        .isEqualTo("now");
  }

  @Test
  void messageToAnIdleAgentStartsTurnAtOnce() {
    holding();
    start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    awaitPrompts(1);
    finishPrompt(0, "end_turn");
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().contains("state IDLE null"));

    commands.tryEmitNext(new AgentCommand.Message("again", false, "key:ann"));
    awaitPrompts(2);

    assertThat(String.join("\n", conversation.calls()))
        .contains("emit MESSAGE u1 {role=user, text=again, author=key:ann, more=false}");
  }

  @Test
  void conversationThatIsIdleForTooLongEndsWithTheLastStatusAndTheReasonIdle() throws Exception {
    idleTimeout = Duration.ofMillis(100);
    agent = agentThatSays("hello", "end_turn");

    final Map<String, Object> outcome =
        done(start(run(preset(PRESET_JSON), "go", true, null, null, 60)));

    assertThat(outcome).containsEntry("status", "success").containsEntry("reason", "idle");
  }

  @Test
  void endingAnIdleConversationClosesTheAgent() throws Exception {
    agent = agentThatSays("hello", "end_turn");
    final var result = start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().contains("state IDLE null"));

    commands.tryEmitNext(new AgentCommand.End(null, null, "key:ann"));

    assertThat(done(result)).containsEntry("status", "success").doesNotContainKey("reason");
  }

  @Test
  void endingTurnInProgressCancelsItAndClosesWhenItEnds() throws Exception {
    holding();
    final var result = start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    awaitPrompts(1);

    commands.tryEmitNext(new AgentCommand.End(null, "enough", "key:ann"));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/cancel"));
    finishPrompt(0, "cancelled");

    assertThat(done(result))
        .containsEntry("status", "incomplete")
        .containsEntry("reason", "enough");
    assertThat(closed.get()).isTrue();
  }

  @Test
  void anAgentThatKeepsRunningAfterTheEndIsClosedAndThenKilled() throws Exception {
    holding();
    stubborn.set(true);
    final var result = start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    awaitPrompts(1);

    commands.tryEmitNext(new AgentCommand.End(null, null, "key:ann"));

    assertThat(done(result)).containsEntry("exitCode", -1).containsEntry("status", "failure");
    assertThat(closed.get()).isTrue();
  }

  @Test
  void endingTaskThatAlreadyClosedItsAgentDoesNotCloseItTwice() {
    stubborn.set(true);
    agent = agentThatSays("hello", "end_turn");
    start(run(preset(PRESET_JSON), "go", false, null, null, 60));
    await().atMost(5, TimeUnit.SECONDS).until(closed::get);

    commands.tryEmitNext(new AgentCommand.End(null, null, "key:ann"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().stream().anyMatch(c -> c.startsWith("end ")));
    assertThat(methods()).doesNotContain("session/cancel");
  }

  @Test
  void taskThatWasInterruptedEndsWithTheInterruptedTurn() throws Exception {
    holding();
    final var result = start(run(preset(PRESET_JSON), "go", false, null, null, 60));
    awaitPrompts(1);

    commands.tryEmitNext(new AgentCommand.Interrupt("key:ann"));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/cancel"));
    finishPrompt(0, "cancelled");

    assertThat(done(result))
        .containsEntry("status", "incomplete")
        .containsEntry("reason", "interrupted");
  }

  @Test
  void anInterruptThatComesBeforeTheSessionOrAfterTheTurnIsIgnored() throws Exception {
    agent = agentThatSays("hello", "end_turn");
    final var result = start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> conversation.calls().contains("state IDLE null"));

    commands.tryEmitNext(new AgentCommand.Interrupt("key:ann"));
    commands.tryEmitNext(new AgentCommand.TerminalInput(new byte[] {1}, "key:ann"));
    commands.tryEmitNext(new AgentCommand.End(null, null, "key:ann"));
    done(result);

    assertThat(methods()).doesNotContain("session/cancel");
  }

  @Test
  void anInterruptBeforeTheSessionExistsIsIgnored() {
    agent = agentThat("session/new", frame -> List.of());
    start(run(preset(PRESET_JSON), "go", true, null, null, 60));
    await().atMost(5, TimeUnit.SECONDS).until(() -> methods().contains("session/new"));

    commands.tryEmitNext(new AgentCommand.Interrupt("key:ann"));
    commands.tryEmitNext(new AgentCommand.End(null, null, "key:ann"));

    await().atMost(5, TimeUnit.SECONDS).until(closed::get);
    assertThat(methods()).doesNotContain("session/cancel");
  }

  @Test
  void anOpenConversationTellsTheEngineItIsAlive() {
    beat = Duration.ofMillis(10);
    final java.util.concurrent.atomic.AtomicInteger beats =
        new java.util.concurrent.atomic.AtomicInteger();
    agent = agentThat("session/prompt", frame -> List.of());
    final AgentRun base = run(preset(PRESET_JSON), "go", true, null, null, 60);

    adapter()
        .run(
            new AgentRun(
                base.message(),
                base.preset(),
                base.spec(),
                base.prompt(),
                null,
                null,
                60,
                conversation,
                log,
                base.who(),
                true,
                Duration.ofSeconds(5),
                beats::incrementAndGet,
                null))
        .subscribe();

    await().atMost(5, TimeUnit.SECONDS).until(() -> beats.get() >= 2);
    assertThat(beats.get()).isGreaterThanOrEqualTo(2);
  }
}
