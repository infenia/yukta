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
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.process.ProcessExecutionResult;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The terminal adapter against a host that is played by the test. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports"
})
class PtyAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String STATUS = "status";

  private final List<JsonNode> written = new CopyOnWriteArrayList<>();
  private final Sinks.Many<AgentCommand> commands = Sinks.many().unicast().onBackpressureBuffer();
  private final RecordingConversation conversation = new RecordingConversation(commands.asFlux());
  private final Sinks.One<ProcessExecutionResult> exit = Sinks.one();
  private final AtomicReference<PluginLogSink> sink = new AtomicReference<>();
  private final List<String> logged = new CopyOnWriteArrayList<>();
  private final ProcessExecutorGateway gateway = mock(ProcessExecutorGateway.class);

  /** Whether the played host leaves when it is told to close. */
  private final java.util.concurrent.atomic.AtomicBoolean leavesOnClose =
      new java.util.concurrent.atomic.AtomicBoolean(true);

  private PtyAdapter adapter() {
    return adapter(Duration.ofSeconds(30));
  }

  private PtyAdapter adapter(final Duration beat) {
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
                    if ("close".equals(frame.path("t").asString()) && leavesOnClose.get()) {
                      // the host hangs the program up: a signal exit code
                      send(Map.of("t", "exit", "code", 129));
                      exit.tryEmitValue(
                          new ProcessExecutionResult(129, List.of(), List.of(), 1, false, false));
                    }
                  }

                  @Override
                  public void close() {
                    // the adapter closes with a frame, not the pipe
                  }
                });
            return exit.asMono();
          });
        });
    return new PtyAdapter(gateway, JSON, beat, Duration.ofMillis(150));
  }

  private static AgentPreset preset(final String pty) {
    return AgentPreset.parse(
        JSON.readTree(
            "{\"id\":\"terminal\",\"protocol\":\"pty\",\"command\":[],\"pty\":" + pty + "}"));
  }

  private AgentRun run(final AgentPreset preset, final String prompt, final boolean inArgs) {
    return new AgentRun(
        DefaultMessage.create(UUID.randomUUID(), "in"),
        preset,
        ProcessExecutionSpec.builder()
            .launcher(List.of("yukta-cli", "pty-host", "--cols", "100", "--rows", "30", "--"))
            .command(List.of("some-agent"))
            .build(),
        prompt,
        null,
        null,
        60,
        conversation,
        (stream, line, level) -> logged.add(stream + " " + line),
        new InputIdentity("s", "w", "e", "n"),
        false,
        Duration.ofSeconds(30),
        () -> {},
        null,
        inArgs);
  }

  private CompletableFuture<Map<String, Object>> start(final AgentRun run) {
    final CompletableFuture<Map<String, Object>> result = adapter().run(run).toFuture();
    await().atMost(2, TimeUnit.SECONDS).until(() -> sink.get() != null);
    return result;
  }

  private void send(final Map<String, Object> frame) {
    sink.get().line("stdout", JSON.writeValueAsString(frame), null);
  }

  private void screen(final String text) {
    send(
        new LinkedHashMap<>(
            Map.of("t", "screen", "cols", 100, "rows", 30, "text", text, "cursorRow", 1)));
  }

  /** What was typed so far, as text. */
  private String typed() {
    return written.stream()
        .filter(frame -> "in".equals(frame.path("t").asString()))
        .map(frame -> Base64.getDecoder().decode(frame.path("d").asString()))
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining());
  }

  private List<String> frames() {
    return written.stream().map(f -> f.path("t").asString()).toList();
  }

  private Map<String, Object> done(final CompletableFuture<Map<String, Object>> result)
      throws java.util.concurrent.ExecutionException,
          InterruptedException,
          java.util.concurrent.TimeoutException {
    return result.get(5, TimeUnit.SECONDS);
  }

  @Test
  void theAdapterSpeaksThePtyProtocol() {
    assertThat(adapter().protocol()).isEqualTo(AgentProtocol.PTY);
  }

  @Test
  void typesThePromptAfterTheDelay_onceAndWithItsEnter() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"type\":\"{prompt}\\r\",\"readyDelayMs\":20}"), "hello", false));

    await().atMost(2, TimeUnit.SECONDS).until(() -> "hello\r".equals(typed()));
    send(Map.of("t", "exit", "code", 0));
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));

    final Map<String, Object> outcome = done(result);
    assertThat(outcome).containsEntry(STATUS, "success").containsEntry("exitCode", 0);
    assertThat(typed()).isEqualTo("hello\r");
  }

  @Test
  void typesThePromptWhenTheScreenIsReady_notAgain_andNotBeforeTheDelay() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(
            run(
                preset(
                    "{\"readyPattern\":\"ready>\",\"readyDelayMs\":60000,"
                        + "\"type\":\"{prompt}\\r\"}"),
                "go",
                false));

    screen("booting");
    assertThat(typed()).isEmpty();
    screen("ready> ");
    screen("ready> again");
    assertThat(typed()).isEqualTo("go\r");

    send(Map.of("t", "exit", "code", 0));
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);
  }

  @Test
  void promptInTheArgumentsIsNotTyped() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":10}"), "hello", true));

    await().pollDelay(Duration.ofMillis(100)).until(() -> true);
    assertThat(typed()).isEmpty();
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);
  }

  @Test
  void messagesInterruptsKeysAndResizesGoToTheHost() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000,\"submit\":\"\\n\"}"), "p", false));

    commands.tryEmitNext(new AgentCommand.Message("do it", false, "arun"));
    commands.tryEmitNext(new AgentCommand.Message("stop, do this", true, "arun"));
    commands.tryEmitNext(new AgentCommand.Interrupt("arun"));
    commands.tryEmitNext(
        new AgentCommand.TerminalInput("ls\r".getBytes(StandardCharsets.UTF_8), "arun"));
    commands.tryEmitNext(new AgentCommand.TerminalResize(132, 43));
    await().atMost(2, TimeUnit.SECONDS).until(() -> frames().contains("resize"));

    assertThat(typed()).isEqualTo("do it\n\u0003stop, do this\n\u0003ls\r");
    final JsonNode resize = written.get(written.size() - 1);
    assertThat(resize.path("cols").asInt()).isEqualTo(132);
    assertThat(resize.path("rows").asInt()).isEqualTo(43);
    assertThat(conversation.calls())
        .anyMatch(c -> c.startsWith("emit MESSAGE u1") && c.contains("author=arun"));

    commands.tryEmitNext(new AgentCommand.End(null, null, "arun"));
    assertThat(done(result)).containsEntry(STATUS, "success");
  }

  @Test
  void outputAndScreensBecomeTerminalEvents_andTheLastScreenIsTheText() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));

    send(
        Map.of(
            "t",
            "out",
            "d",
            Base64.getEncoder().encodeToString("hi".getBytes(StandardCharsets.UTF_8))));
    screen("first\n\n");
    screen("the answer is 42\n\n\n");
    send(Map.of("t", "exit", "code", 0));
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));

    final Map<String, Object> outcome = done(result);
    assertThat(outcome).containsEntry("text", "the answer is 42");
    assertThat(conversation.calls())
        .anyMatch(c ->
            c.startsWith("emit TERMINAL null") && c.contains("data=aGk=") && c.contains("cols=100"))
        .anyMatch(c -> c.startsWith("emit TERMINAL null") && c.contains("settled=true"))
        .anyMatch(c -> c.startsWith("end success"));
    assertThat(logged)
        .containsExactly("agent terminal started: some-agent", "agent terminal ended: success");
  }

  @Test
  void screenThatWantsAttentionWaitsUntilItChanges() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(
            run(
                preset("{\"readyDelayMs\":60000,\"attentionPattern\":\"\\\\[y/n\\\\]\"}"),
                "p",
                false));

    screen("Proceed? [y/n]");
    screen("Proceed? [y/n] y");
    screen("working");
    assertThat(conversation.calls().stream().filter(c -> c.startsWith("state")).toList())
        .containsExactly(
            "state RUNNING null",
            "state WAITING the terminal wants attention",
            "state RUNNING null");

    commands.tryEmitNext(new AgentCommand.End(null, null, "arun"));
    done(result);
  }

  @Test
  void screenThatIsDoneEndsTheTerminalAsSuccess_whateverTheHostExitCodeWas() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000,\"donePattern\":\"DONE\"}"), "p", false));

    screen("all DONE");

    final Map<String, Object> outcome = done(result);
    assertThat(outcome).containsEntry(STATUS, "success");
    assertThat(frames()).contains("close");
  }

  @Test
  void endTakesTheOutcomeItGives() throws Exception {
    final CompletableFuture<Map<String, Object>> ended =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));
    commands.tryEmitNext(new AgentCommand.End("incomplete", "enough", "arun"));
    assertThat(done(ended)).containsEntry(STATUS, "incomplete").containsEntry("reason", "enough");
  }

  @Test
  void programThatExitsWithNonZeroCodeFails() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));
    send(Map.of("t", "exit", "code", 3));
    exit.tryEmitValue(new ProcessExecutionResult(3, List.of(), List.of(), 1, false, false));

    assertThat(done(result))
        .containsEntry(STATUS, "failure")
        .containsEntry("exitCode", 3)
        .containsEntry("reason", "the program exited with code 3");
  }

  @Test
  void hostThatStaysAfterCloseIsKilled() throws Exception {
    leavesOnClose.set(false);
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));
    commands.tryEmitNext(new AgentCommand.End(null, null, "arun"));

    assertThat(done(result)).containsEntry(STATUS, "success");
  }

  @Test
  void lineThatIsNotFrameAndStandardErrorBecomeNotices() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));
    sink.get().line("stdout", "not json", null);
    sink.get().line("stderr", "oops", null);
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);

    assertThat(conversation.calls().stream().filter(c -> c.startsWith("emit NOTICE")).count())
        .isEqualTo(2);
  }

  @Test
  void processThatTimedOutFails() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));
    exit.tryEmitValue(new ProcessExecutionResult(-1, List.of(), List.of(), 1, true, false));

    assertThat(done(result))
        .containsEntry(STATUS, "failure")
        .containsEntry("reason", "timed out after 60s");
  }

  @Test
  void readyScreenAndTheDelayTypeThePromptOnlyOnce() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(
            run(
                preset("{\"readyPattern\":\"ready>\",\"readyDelayMs\":300,\"type\":\"{prompt}\"}"),
                "go",
                false));

    screen("ready> ");
    await().atMost(2, TimeUnit.SECONDS).until(() -> "go".equals(typed()));
    Thread.sleep(500);
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);

    assertThat(typed()).isEqualTo("go");
  }

  @Test
  void promptlessRunTypesNothing() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":10}"), "", false));

    Thread.sleep(200);
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);

    assertThat(typed()).isEmpty();
  }

  @Test
  void doneScreenShownTwiceClosesTheHostOnce() throws Exception {
    leavesOnClose.set(false);
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000,\"donePattern\":\"DONE\"}"), "p", false));

    screen("DONE");
    screen("DONE again");
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);

    assertThat(frames().stream().filter("close"::equals)).hasSize(1);
  }

  @Test
  void errorsOnStandardErrorAreNoticedAsErrorsAndSizelessScreensKeepTheSize() throws Exception {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));

    sink.get().line("stderr", "boom", com.infenia.yukta.plugin.core.LogLevel.ERROR);
    send(Map.of("t", "screen", "text", "no size"));
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));
    done(result);

    assertThat(conversation.calls()).anyMatch(c -> c.contains("ERROR"));
  }

  @Test
  void hugeLastScreenIsCutAndBadLauncherSizeFallsBack() throws Exception {
    final AgentRun badLauncher =
        new AgentRun(
            DefaultMessage.create(UUID.randomUUID(), "in"),
            preset("{\"readyDelayMs\":60000}"),
            ProcessExecutionSpec.builder()
                .launcher(List.of("yukta-cli", "--cols", "wide", "--rows"))
                .command(List.of("some-agent"))
                .build(),
            "p",
            null,
            null,
            60,
            conversation,
            (stream, line, level) -> logged.add(stream + " " + line),
            new InputIdentity("s", "w", "e", "n"),
            false,
            Duration.ofSeconds(30),
            () -> {},
            null,
            false);
    final CompletableFuture<Map<String, Object>> result = start(badLauncher);

    screen("x".repeat(70_000));
    exit.tryEmitValue(new ProcessExecutionResult(0, List.of(), List.of(), 1, false, false));

    assertThat(done(result).get("text").toString()).hasSize(64 * 1024);
  }

  @Test
  void launcherWithoutSizeFlagsUsesDefaults_andHostIsToldItIsAlive() throws Exception {
    final Runnable beat = mock(Runnable.class);
    final AgentRun run =
        new AgentRun(
            DefaultMessage.create(UUID.randomUUID(), "in"),
            preset("{\"readyDelayMs\":60000}"),
            ProcessExecutionSpec.builder()
                .launcher(List.of("yukta-cli"))
                .command(List.of("some-agent"))
                .build(),
            "p",
            null,
            null,
            60,
            conversation,
            (stream, line, level) -> logged.add(stream + " " + line),
            new InputIdentity("s", "w", "e", "n"),
            false,
            Duration.ofSeconds(30),
            beat,
            null,
            false);
    final var subscription = adapter(Duration.ofMillis(20)).run(run).subscribe();
    org.mockito.Mockito.verify(beat, org.mockito.Mockito.timeout(2000).atLeastOnce()).run();
    subscription.dispose();

    assertThat(conversation.calls()).anyMatch(c -> c.contains("end"));
  }

  @Test
  void cancellingTheRunEndsTheConversationIncomplete() {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));

    result.cancel(true);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> conversation.calls().toString().contains("cancelled"));
    assertThat(result).isCancelled();
  }

  @Test
  void hostThatFailsEndsTheConversationAsFailed() {
    final CompletableFuture<Map<String, Object>> result =
        start(run(preset("{\"readyDelayMs\":60000}"), "p", false));

    exit.tryEmitError(new IllegalStateException("host died"));

    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> conversation.calls().toString().contains("host died"));
    assertThat(result).isCompletedExceptionally();
  }
}
