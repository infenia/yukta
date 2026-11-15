// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;

/**
 * The turns of an agent that takes messages on standard input, against a fake agent: a shell loop
 * that answers each user line with a result, treats the text HOLD as a turn that never ends on its
 * own and ends a turn with an error when it is interrupted.
 */
@NoArgsConstructor
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods", "PMD.ExcessiveImports"})
class ChatScenariosTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SCRIPT =
      """
      n=0
      while IFS= read -r line; do
        case "$line" in
          *'"subtype":"interrupt"'*)
            if [ "$held" = ok ]; then
              echo '{"type":"result","subtype":"success","is_error":false,"result":"too late","session_id":"s"}'
            else
              echo '{"type":"result","subtype":"error_during_execution","is_error":true,"result":"","session_id":"s"}'
            fi ;;
          *'"content":"HOLDOK"'*) held=ok ;;
          *'"content":"HOLD"'*) : ;;
          *'"content":"DIE"'*) exit 3 ;;
          *'"content":"ASKME"'*)
            echo '{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{},"description":"d"}}'
            read -r reply
            m=$(printf %s "$reply" | sed -n 's/.*"message":"\\([^"]*\\)".*/\\1/p')
            echo "{\\"type\\":\\"result\\",\\"subtype\\":\\"success\\",\\"is_error\\":false,\\"result\\":\\"$m\\",\\"session_id\\":\\"s\\"}" ;;
          *'"type":"control_request"'*)
            echo '{"type":"result","subtype":"success","is_error":false,"result":"asked","session_id":"s"}' ;;
          *)
            n=$((n+1))
            t=$(printf %s "$line" | sed -n 's/.*"content":"\\([^"]*\\)".*/\\1/p')
            echo "{\\"type\\":\\"result\\",\\"subtype\\":\\"success\\",\\"is_error\\":false,\\"result\\":\\"turn $n: $t\\",\\"session_id\\":\\"s\\"}" ;;
        esac
      done
      """;

  private static final String FIXTURE_PRESET =
      """
      {"id":"@ID@","description":"fixture","command":["sh","-c","exit 9"],
       "capabilities":{"ask":true},
       "interactive":{"command":["sh","@SCRIPT@"],
         "userMessage":{"type":"user","message":{"role":"user","content":"{prompt}"}},
         "requestWhen":{"type":"control_request","request.subtype":"can_use_tool"},
         "requestId":"request_id","tool":"request.tool_name","input":"request.input","description":"request.description",
         "allow":{"type":"control_response","response":{"request_id":"{id}"}},
         "deny":{"type":"control_response","response":{"request_id":"{id}","message":"{message}"}},
         "steer":@STEER@,
         "interrupt":{"type":"control_request","request_id":"{id}","request":{"subtype":"interrupt"}}},
       "output":{"resultWhen":{"type":"result"},"text":"result","sessionId":"session_id","isError":"is_error","subtype":"subtype"}}
      """;

  @TempDir private Path dir;

  private AgentSessionPlugin plugin;

  /** The commands the test sends to the running agent. */
  private final Sinks.Many<AgentCommand> commands = Sinks.many().unicast().onBackpressureBuffer();

  /** What the plugin wrote to the conversation. */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  private AgentConversationSpec opened;

  /** Whether the engine's heartbeat is in the context of the next run. */
  private boolean beating;

  private final java.util.concurrent.atomic.AtomicInteger beats =
      new java.util.concurrent.atomic.AtomicInteger();

  @BeforeEach
  void setUp() throws IOException {
    final String script = dir.resolve("agent.sh").toString();
    Files.writeString(dir.resolve("agent.sh"), SCRIPT);
    Files.writeString(dir.resolve("queuer.json"), preset("queuer", script, "false"));
    Files.writeString(
        dir.resolve("deaf.json"),
        preset("deaf", script, "false")
            .replaceFirst(",\\s*\"interrupt\":\\{.*\\}\\},\\s*\"output\"", "},\n \"output\""));
    Files.writeString(dir.resolve("steerer.json"), preset("steerer", script, "true"));
    final VariableResolver resolver = mock(VariableResolver.class);
    when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(i -> Mono.just(i.getArgument(0)));
    final ProcessExecutorGateway gateway = new ProcessExecutorGateway();
    org.springframework.test.util.ReflectionTestUtils.setField(gateway, "allowAny", true);
    plugin =
        new AgentSessionPlugin(
            gateway,
            resolver,
            new AgentHarnesses(JSON, dir.toString()),
            new InMemoryAgentSessionStore(),
            provider(mock(InputGateway.class)),
            provider(
                (AgentConversationGateway)
                    spec -> {
                      opened = spec;
                      return Mono.just(new Handle());
                    }),
            JSON,
            Duration.ofMillis(50));
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(final T value) {
    final ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private static String preset(final String id, final String script, final String steer) {
    return FIXTURE_PRESET.replace("@ID@", id).replace("@SCRIPT@", script).replace("@STEER@", steer);
  }

  private Runnable beat() {
    return beats::incrementAndGet;
  }

  private CompletableFuture<Message<?>> start(
      final String harness, final Map<String, Object> extra, final String prompt) {
    final Map<String, Object> config = new ConcurrentHashMap<>(extra);
    config.putAll(Map.of("harness", harness, "prompt", prompt));
    return CompletableFuture.supplyAsync(() ->
        plugin
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
                        "n-1")
                    .putAll(
                        beating
                            ? Context.of(WorkflowContext.HEARTBEAT_KEY, beat()).readOnly()
                            : Context.empty().readOnly()))
            .collectList()
            .blockOptional(Duration.ofSeconds(30))
            .orElseThrow()
            .getFirst());
  }

  private void awaitCall(final String fragment) {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> calls.stream().anyMatch(c -> c.contains(fragment)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payload(final Message<?> message) {
    return (Map<String, Object>) message.payload();
  }

  private void send(final AgentCommand command) {
    assertThat(commands.tryEmitNext(command)).isEqualTo(Sinks.EmitResult.OK);
  }

  @Test
  void whenMessageDuringTurnWaitsAndIsSentWhenTheTurnEnds() throws Exception {
    final var running = start("queuer", Map.of(), "HOLD");
    awaitCall("state RUNNING");

    send(new AgentCommand.Message("later", false, "key:ann"));
    awaitCall("queued 1");
    send(new AgentCommand.Interrupt("key:ann"));
    final Message<?> out = running.get();

    assertThat(calls)
        .anyMatch(c ->
            c.contains("MESSAGE u1") && c.contains("queued=true") && c.contains("author=key:ann"))
        .anyMatch(c ->
            c.startsWith("emit RESULT")
                && c.contains("status=incomplete")
                && c.contains("reason=interrupted"))
        .anyMatch("queued 0"::equals)
        .anyMatch(c -> c.startsWith("emit RESULT") && c.contains("turn 1: later"));
    assertThat(out.sourcePort()).isEqualTo("success");
    assertThat(payload(out)).containsEntry("text", "turn 1: later").containsEntry("turn", 2);
    assertThat(opened.capabilities()).contains("message", "interrupt").doesNotContain("steer");
  }

  @Test
  void whenMessageWithInterruptStopsTheTurnFirst() throws Exception {
    final var running = start("queuer", Map.of(), "HOLD");
    awaitCall("state RUNNING");

    send(new AgentCommand.Message("now", true, "key:ann"));
    final Message<?> out = running.get();

    assertThat(payload(out)).containsEntry("text", "turn 1: now");
  }

  @Test
  void whenHarnessThatSteersTakesTheMessageInTheSameTurn() throws Exception {
    final var running = start("steerer", Map.of(), "HOLD");
    awaitCall("state RUNNING");

    send(new AgentCommand.Message("also", false, "key:ann"));
    final Message<?> out = running.get();

    assertThat(calls).noneMatch(c -> c.contains("queued=true"));
    assertThat(payload(out)).containsEntry("text", "turn 1: also").containsEntry("turn", 1);
    assertThat(opened.capabilities()).contains("steer");
  }

  @Test
  void whenConversationStaysIdleBetweenTurnsUntilItIsEnded() throws Exception {
    final var running = start("queuer", Map.of("mode", "CONVERSATION"), "one");
    awaitCall("state IDLE null");

    send(new AgentCommand.Message("two", false, "key:ann"));
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> calls.stream().filter("state IDLE null"::equals).count() == 2);
    assertThat(running).isNotDone();
    send(new AgentCommand.End(null, null, "key:ann"));
    final Message<?> out = running.get();

    assertThat(out.sourcePort()).isEqualTo("success");
    assertThat(payload(out)).containsEntry("text", "turn 2: two").containsEntry("turn", 2);
    assertThat(calls).last().isEqualTo("end success null");
  }

  @Test
  void endingWithAnOutcomeOverridesTheLastTurnsStatus() throws Exception {
    final var running = start("queuer", Map.of("mode", "CONVERSATION"), "one");
    awaitCall("state IDLE null");

    send(new AgentCommand.End("failure", "enough", "key:ann"));
    final Message<?> out = running.get();

    assertThat(out.sourcePort()).isEqualTo("failure");
    assertThat(payload(out)).containsEntry("reason", "enough");
  }

  @Test
  void whenConversationThatWaitsTooLongEndsWithTheLastTurnsStatusAndTheReasonIdle()
      throws Exception {
    final Message<?> out =
        start("queuer", Map.of("mode", "CONVERSATION", "idleTimeout", 1), "one").get();

    assertThat(out.sourcePort()).isEqualTo("success");
    assertThat(payload(out)).containsEntry("reason", "idle").containsEntry("text", "turn 1: one");
  }

  @Test
  void endingTurnThatIsRunningStopsItFirst() throws Exception {
    final var running = start("queuer", Map.of(), "HOLD");
    awaitCall("state RUNNING");

    send(new AgentCommand.End(null, "stop", "key:ann"));
    final Message<?> out = running.get();

    assertThat(out.sourcePort()).isEqualTo("incomplete");
    assertThat(payload(out)).containsEntry("reason", "stop");
  }

  @Test
  void whenProcessThatDiesMidTurnFailsTheNode() throws Exception {
    final Message<?> out = start("queuer", Map.of(), "DIE").get();

    assertThat(out.sourcePort()).isEqualTo("failure");
  }

  @Test
  void whenTurnThatRunsTooLongTimesOut() throws Exception {
    final Message<?> out = start("queuer", Map.of("timeout", 1), "HOLD").get();

    assertThat(out.sourcePort()).isEqualTo("failure");
    assertThat(payload(out).get("reason").toString()).contains("timed out");
  }

  @Test
  void whenConversationThatRunsTooLongTimesOut() throws Exception {
    final Message<?> out =
        start("queuer", Map.of("mode", "CONVERSATION", "conversationTimeout", 1), "one").get();

    assertThat(payload(out).get("reason").toString()).contains("timed out");
  }

  @Test
  void whenRequestIsRefusedWhenApprovalsAreOff() throws Exception {
    final var running = start("queuer", Map.of("mode", "CONVERSATION"), "one");
    awaitCall("state IDLE null");

    send(new AgentCommand.End(null, null, "key:ann"));
    running.get();

    assertThat(opened.capabilities()).doesNotContain("ask");
  }

  @Test
  void theNodeSteerAudienceIsTheAudienceOfTheConversation() throws Exception {
    final var running =
        start("queuer", Map.of("steerAudience", List.of("key:ann", "supervisor:*")), "one");
    running.get();

    assertThat(opened.steerAudience().selectors()).containsExactly("key:ann", "supervisor:*");
  }

  @Test
  void theDefaultSteerAudienceIsTheOperators() throws Exception {
    start("queuer", Map.of(), "one").get();

    assertThat(opened.steerAudience().selectors()).containsExactly("role:OPERATOR");
  }

  @Test
  void anOpenConversationTellsTheEngineItIsAlive() throws Exception {
    beating = true;
    final var running = start("queuer", Map.of("mode", "CONVERSATION"), "one");
    awaitCall("state IDLE null");

    await().atMost(Duration.ofSeconds(20)).until(() -> beats.get() >= 2);
    assertThat(beats.get()).isGreaterThanOrEqualTo(2);
    send(new AgentCommand.End(null, null, "key:ann"));
    running.get();
  }

  @Test
  void turnThatEndsWellAfterInterruptStaysAsItEnded() throws Exception {
    final var running = start("queuer", Map.of(), "HOLDOK");
    awaitCall("state RUNNING");

    send(new AgentCommand.Interrupt("key:ann"));
    final Message<?> out = running.get();

    assertThat(out.sourcePort()).isEqualTo("success");
    assertThat(payload(out)).containsEntry("text", "too late");
  }

  @Test
  void anInterruptWhileTheAgentIsIdleDoesNothing() throws Exception {
    final var running = start("queuer", Map.of("mode", "CONVERSATION"), "one");
    awaitCall("state IDLE null");

    send(new AgentCommand.Interrupt("key:ann"));
    send(new AgentCommand.TerminalResize(80, 24));
    send(new AgentCommand.End(null, null, "key:ann"));
    final Message<?> out = running.get();

    assertThat(payload(out)).containsEntry("turn", 1);
  }

  @Test
  void anInterruptTheHarnessCannotTakeIsIgnored() throws Exception {
    final var running = start("deaf", Map.of(), "HOLD");
    awaitCall("state RUNNING");

    send(new AgentCommand.Interrupt("key:ann"));
    send(new AgentCommand.End("incomplete", "bye", "key:ann"));
    final Message<?> out = running.get();

    assertThat(out.sourcePort()).isEqualTo("incomplete");
    assertThat(opened.capabilities()).doesNotContain("interrupt");
  }

  @Test
  void requestIsRefusedWhenApprovalsAreOffForTheNode() throws Exception {
    final Message<?> out = start("queuer", Map.of(), "ASKME").get();

    assertThat(payload(out)).containsEntry("text", "approvals are off for this node");
  }

  /** The handle of the conversation: the commands come from the test. */
  private final class Handle implements AgentConversationHandle {

    @Override
    @SuppressWarnings("PMD.ShortMethodName")
    public String id() {
      return "c-1";
    }

    @Override
    public void emit(
        final AgentEventKind kind, final String itemId, final Map<String, Object> data) {
      calls.add("emit " + kind + " " + itemId + " " + data);
    }

    @Override
    public void state(final AgentState state, final String reason) {
      calls.add("state " + state + " " + reason);
    }

    @Override
    public void queued(final int count) {
      calls.add("queued " + count);
    }

    @Override
    public Flux<AgentCommand> commands() {
      return commands.asFlux();
    }

    @Override
    public void end(final String outcome, final String reason) {
      calls.add("end " + outcome + " " + reason);
    }
  }
}
