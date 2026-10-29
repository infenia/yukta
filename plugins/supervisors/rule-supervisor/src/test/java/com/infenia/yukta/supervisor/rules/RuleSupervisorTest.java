// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.core.LogLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class RuleSupervisorTest {

  private static final String RETRY_RULES =
      """
      {"rules":[{"id":"retry","when":{"event":"NodeStatusChanged","nodeId":"n1","status":"SUCCESS",
        "if":"metadata['outcome'] == 'RETRY' and attempt < 2"},
        "then":{"command":"RestartFromNode","from":"metadata.restartFrom"}}]}
      """;

  /** A clock the test moves by hand. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    /** Moves the clock forward. */
    /* default */ void advance(final Duration amount) {
      now = now.plus(amount);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static RuleSupervisor supervisor(final String rulesJson) {
    return new RuleSupervisor(RuleSupervisor.read(rulesJson), Clock.systemUTC());
  }

  private static ExecutionEvent.NodeStatusChanged node(
      final String execution, final String status, final Map<String, Object> metadata) {
    return new ExecutionEvent.NodeStatusChanged(
        execution, "wf", "n1", "HTTP_REQUEST", status, metadata, Instant.now());
  }

  private static List<ExecutionControlCommand> run(
      final RuleSupervisor supervisor, final ExecutionEvent... events) {
    return supervisor.supervise(Flux.just(events)).collectList().block();
  }

  private static Decision review(
      final RuleSupervisor supervisor, final ExecutionControlCommand command) {
    return supervisor.review(new ControlRequest(command, null)).block();
  }

  @Test
  void supervise_restartFromNode_readsTargetFromMetadataAndGeneratesNewId() {
    final var commands =
        run(
            supervisor(RETRY_RULES),
            node("e1", "SUCCESS", Map.of("outcome", "RETRY", "restartFrom", "spotless")));

    assertThat(commands)
        .singleElement()
        .isInstanceOfSatisfying(
            ExecutionControlCommand.RestartFromNodeCommand.class,
            restart -> {
              assertThat(restart.executionId()).isEqualTo("e1");
              assertThat(restart.fromNodeId()).isEqualTo("spotless");
              assertThat(restart.newExecutionId()).isNotBlank();
            });
  }

  @Test
  void supervise_restartFromStart_restartsWholeRun() {
    final var commands =
        run(
            supervisor(RETRY_RULES),
            node("e1", "SUCCESS", Map.of("outcome", "RETRY", "restartFrom", "START")));

    assertThat(commands).singleElement().isInstanceOf(ExecutionControlCommand.RestartCommand.class);
  }

  @Test
  void supervise_restartLoop_stopsWhenAttemptBudgetIsSpent() {
    final var rules = supervisor(RETRY_RULES);
    final Map<String, Object> retry = Map.of("outcome", "RETRY", "restartFrom", "spotless");
    String execution = "e0";
    int restarts = 0;
    while (true) {
      final var commands = run(rules, node(execution, "SUCCESS", retry));
      if (commands.isEmpty()) {
        break;
      }
      execution =
          ((ExecutionControlCommand.RestartFromNodeCommand) commands.getFirst()).newExecutionId();
      restarts++;
    }

    assertThat(restarts).isEqualTo(2);
  }

  @Test
  void supervise_restartRule_literalTargetStartAndMissingMetadata() {
    final var rules =
        supervisor(
            """
            {"rules":[
             {"id":"lit","when":{"status":"FAILURE"},"then":{"command":"RestartFromNode","from":"n0"}},
             {"id":"dflt","when":{"status":"TIMED_OUT"},"then":{"command":"RestartFromNode"}},
             {"id":"all","when":{"status":"CANCELLED"},"then":{"command":"Restart"}},
             {"id":"meta","when":{"status":"QUEUED"},"then":{"command":"RestartFromNode","from":"metadata.x"}}]}
            """);

    final var commands =
        run(
            rules,
            node("e1", "FAILURE", null),
            node("e1", "TIMED_OUT", null),
            node("e1", "CANCELLED", null),
            node("e1", "QUEUED", null),
            node("e1", "QUEUED", Map.of("x", "n7")),
            node("e1", "QUEUED", Map.of("y", "z")));

    assertThat(commands)
        .extracting(c -> c.getClass().getSimpleName())
        .containsExactly(
            "RestartFromNodeCommand",
            "RestartFromNodeCommand",
            "RestartCommand",
            "RestartCommand",
            "RestartFromNodeCommand",
            "RestartCommand");
    assertThat(((ExecutionControlCommand.RestartFromNodeCommand) commands.get(0)).fromNodeId())
        .isEqualTo("n0");
    assertThat(((ExecutionControlCommand.RestartFromNodeCommand) commands.get(1)).fromNodeId())
        .isEqualTo("n1");
    assertThat(((ExecutionControlCommand.RestartFromNodeCommand) commands.get(4)).fromNodeId())
        .isEqualTo("n7");
  }

  @Test
  void supervise_manyRestarts_forgetsTheOldestAttempts() {
    final var rules = supervisor(RETRY_RULES);
    final Map<String, Object> retry = Map.of("outcome", "RETRY", "restartFrom", "spotless");
    final ExecutionEvent[] events = new ExecutionEvent[1100];
    for (int i = 0; i < events.length; i++) {
      events[i] = node("e" + i, "SUCCESS", retry);
    }

    assertThat(run(rules, events)).hasSize(events.length);
  }

  @Test
  void id_isRules() {
    assertThat(supervisor("{}").id()).isEqualTo("rules");
  }

  @Test
  void supervise_matchingEvent_sendsCommandToItsExecution() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","when":{"event":"NodeStatusChanged","status":"FAILURE",
              "module":"HTTP_REQUEST","nodeId":"n1","workflowId":"wf"},
              "then":{"command":"PauseWorkflow"}}]}
            """);

    assertThat(run(rules, node("e1", "FAILURE", null), node("e2", "SUCCESS", null)))
        .containsExactly(new ExecutionControlCommand.PauseWorkflowCommand("e1"));
  }

  @Test
  void supervise_eventDoesNotMatchAnyField_isIgnored() {
    final var rules =
        supervisor(
            """
            {"rules":[
             {"id":"a","when":{"event":"LogLine"},"then":{"command":"PauseWorkflow"}},
             {"id":"b","when":{"module":"OTHER"},"then":{"command":"PauseWorkflow"}},
             {"id":"c","when":{"nodeId":"n9"},"then":{"command":"PauseWorkflow"}},
             {"id":"d","when":{"workflowId":"other"},"then":{"command":"PauseWorkflow"}}]}
            """);

    assertThat(run(rules, node("e1", "FAILURE", null))).isEmpty();
  }

  @Test
  void supervise_conditionOverMetadata_decidesTheMatch() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","when":{"status":"FAILURE","if":"metadata['code'] == 500"},
              "then":{"command":"PauseWorkflow"}}]}
            """);

    assertThat(run(rules, node("e1", "FAILURE", Map.of("code", 404)))).isEmpty();
    assertThat(run(rules, node("e1", "FAILURE", Map.of("code", 500)))).hasSize(1);
  }

  @Test
  void supervise_conditionThatCannotBeEvaluated_doesNotMatch() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","when":{"if":"metadata['code'].toString() == '500'"},
              "then":{"command":"PauseWorkflow"}}]}
            """);

    assertThat(run(rules, node("e1", "FAILURE", Map.of("code", 500)))).isEmpty();
  }

  @Test
  void supervise_countWithinWindow_triggersOncePerBurst() {
    final ManualClock clock = new ManualClock();
    final var rules =
        new RuleSupervisor(
            RuleSupervisor.read(
                """
                {"rules":[{"id":"r","when":{"status":"FAILURE","count":3,"withinSeconds":10},
                  "then":{"command":"StopWorkflow","mode":"KILL","reason":"too many"}}]}
                """),
            clock);
    final var failure = node("e1", "FAILURE", null);

    assertThat(run(rules, failure, failure)).isEmpty();
    clock.advance(Duration.ofSeconds(11));
    assertThat(run(rules, failure, failure)).isEmpty();
    assertThat(run(rules, failure))
        .containsExactly(
            new ExecutionControlCommand.StopWorkflowCommand("e1", "too many", StopMode.KILL));
    assertThat(run(rules, failure, failure)).isEmpty();
    assertThat(run(rules, node("e2", "FAILURE", null), node("e2", "FAILURE", null))).isEmpty();
  }

  @Test
  void supervise_countWithoutWindow_defaultsToOneMinute() {
    final ManualClock clock = new ManualClock();
    final var rules =
        new RuleSupervisor(
            RuleSupervisor.read(
                """
                {"rules":[{"id":"r","when":{"count":2},"then":{"command":"PauseWorkflow"}}]}
                """),
            clock);
    final var event = node("e1", "FAILURE", null);

    assertThat(run(rules, event)).isEmpty();
    clock.advance(Duration.ofSeconds(59));
    assertThat(run(rules, event)).hasSize(1);
  }

  @Test
  void supervise_everyCommandTemplate_isBuiltForTheTriggeringNode() {
    final var rules =
        supervisor(
            """
            {"rules":[
             {"id":"1","when":{},"then":{"command":"ResumeWorkflow"}},
             {"id":"2","when":{},"then":{"command":"StopWorkflow"}},
             {"id":"3","when":{},"then":{"command":"PauseNode"}},
             {"id":"4","when":{},"then":{"command":"ResumeNode"}},
             {"id":"5","when":{},"then":{"command":"StopNode","immediate":true,"reason":"x"}},
             {"id":"6","when":{},"then":{"command":"StopNode"}}]}
            """);

    assertThat(run(rules, node("e1", "FAILURE", null)))
        .containsExactly(
            new ExecutionControlCommand.ResumeWorkflowCommand("e1"),
            new ExecutionControlCommand.StopWorkflowCommand(
                "e1", "Stopped by rule", StopMode.DRAIN),
            new ExecutionControlCommand.PauseNodeCommand("e1", "n1"),
            new ExecutionControlCommand.ResumeNodeCommand("e1", "n1"),
            new ExecutionControlCommand.StopNodeCommand("e1", "n1", true, "x"),
            new ExecutionControlCommand.StopNodeCommand("e1", "n1", false, "Stopped by rule"));
  }

  @Test
  void supervise_nodeCommandOnEventWithoutNode_isIgnored() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"p","when":{"event":"WorkflowStatusChanged"},
              "then":{"command":"PauseNode"}}]}
            """);

    assertThat(
            run(
                rules,
                new ExecutionEvent.WorkflowStatusChanged("e1", "wf", "FAILURE", Instant.now())))
        .isEmpty();
  }

  @Test
  void supervise_restartFromNodeOnEventWithoutNode_stillMatches() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","when":{"event":"WorkflowStatusChanged"},
              "then":{"command":"RestartFromNode","from":"START"}}]}
            """);

    assertThat(
            run(
                rules,
                new ExecutionEvent.WorkflowStatusChanged("e1", "wf", "FAILURE", Instant.now())))
        .hasSize(1);
  }

  @Test
  void supervise_manyCountedExecutions_forgetsTheOldestMatches() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"c","when":{"status":"FAILURE","count":2},
              "then":{"command":"PauseWorkflow"}}]}
            """);
    final var events = new ExecutionEvent[1100];
    for (int i = 0; i < events.length; i++) {
      events[i] = node("e" + i, "FAILURE", Map.of());
    }

    // one match per run never reaches the count of 2, and the first run's match is forgotten
    assertThat(run(rules, events)).isEmpty();
    assertThat(run(rules, node("e0", "FAILURE", Map.of()))).isEmpty();
  }

  @Test
  void supervise_otherEventKinds_exposeTheirFields() {
    final var rules =
        supervisor(
            """
            {"rules":[
             {"id":"w","when":{"event":"WorkflowStatusChanged","if":"status == 'FAILURE'"},
              "then":{"command":"ResumeWorkflow"}},
             {"id":"c","when":{"event":"ControlOutcome","if":"actor == 'human' and !applied"},
              "then":{"command":"PauseWorkflow"}},
             {"id":"l","when":{"event":"LogLine","if":"line == 'boom' or level == 'ERROR'"},
              "then":{"command":"StopWorkflow"}}]}
            """);
    final Instant now = Instant.now();

    assertThat(
            run(
                rules,
                new ExecutionEvent.WorkflowStatusChanged("e1", "wf", "FAILURE", now),
                new ExecutionEvent.ControlOutcome("e1", "X", "human", false, "no", now),
                new ExecutionEvent.LogLine("e1", "n1", "boom", LogLevel.INFO, now)))
        .hasSize(3);
    assertThat(Fields.eventFields(new ExecutionEvent.EventsMissed(5, now)))
        .containsEntry("status", "EVENTS_MISSED")
        .containsEntry("missed", 5L);
    assertThat(
            run(
                rules,
                new ExecutionEvent.WorkflowStatusChanged("e1", "wf", "SUCCESS", now),
                new ExecutionEvent.ControlOutcome("e1", "X", "rules", true, null, now),
                new ExecutionEvent.LogLine("e1", "n1", "fine", LogLevel.INFO, now)))
        .isEmpty();
    assertThat(run(rules, new ExecutionEvent.LogLine("e1", "n1", "fine", LogLevel.ERROR, now)))
        .hasSize(1);
  }

  @Test
  void review_denyRule_refusesMatchingCommandsOnly() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"no-kill","deny":{"command":"StopWorkflow",
              "if":"mode == 'KILL'","reason":"use DRAIN"}}]}
            """);

    assertThat(
            review(
                rules, new ExecutionControlCommand.StopWorkflowCommand("e1", "r", StopMode.KILL)))
        .isEqualTo(Decision.deny("use DRAIN"));
    assertThat(
            review(
                rules, new ExecutionControlCommand.StopWorkflowCommand("e1", "r", StopMode.DRAIN)))
        .isEqualTo(Decision.ALLOW);
    assertThat(review(rules, new ExecutionControlCommand.PauseWorkflowCommand("e1")))
        .isEqualTo(Decision.ALLOW);
  }

  @Test
  void review_denyWithoutConditionAndReason_alwaysDeniesWithDefaultReason() {
    final var rules =
        supervisor("{\"rules\":[{\"id\":\"frozen\",\"deny\":{\"command\":\"PauseNodeCommand\"}}]}");

    assertThat(review(rules, new ExecutionControlCommand.PauseNodeCommand("e1", "n1")))
        .isEqualTo(Decision.deny("denied by rule frozen"));
  }

  @Test
  void review_conditionOverActorNodeAndReason_seesCommandFields() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","deny":{"command":"StopNode",
              "if":"actor == 'ai' and nodeId == 'n1' and immediate and reason == 'x'"}}]}
            """);
    final var stop = new ExecutionControlCommand.StopNodeCommand("e1", "n1", true, "x");

    assertThat(rules.review(new ControlRequest(stop, "ai")).block().allowed()).isFalse();
    assertThat(rules.review(new ControlRequest(stop, "human")).block().allowed()).isTrue();
  }

  @Test
  void review_conditionThatCannotBeEvaluated_denies() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","deny":{"command":"PauseWorkflow",
              "if":"executionId.length() > 1"}}]}
            """);

    assertThat(review(rules, new ExecutionControlCommand.PauseWorkflowCommand("e1")).allowed())
        .isFalse();
  }

  @Test
  void review_everyNodeCommand_exposesItsNodeId() {
    final List<ExecutionControlCommand> commands =
        List.of(
            new ExecutionControlCommand.PauseNodeCommand("e", "n1"),
            new ExecutionControlCommand.ResumeNodeCommand("e", "n1"),
            new ExecutionControlCommand.StopNodeCommand("e", "n1", false, "r"),
            new ExecutionControlCommand.SkipNodeCommand("e", "n1", true),
            new ExecutionControlCommand.EnableStepModeCommand("e", "n1"),
            new ExecutionControlCommand.DisableStepModeCommand("e", "n1"),
            new ExecutionControlCommand.StepNodeCommand("e", "n1"),
            new ExecutionControlCommand.RestartFromNodeCommand("e", "n1", "e2"),
            new ExecutionControlCommand.SetBreakpointCommand("e", "n1", "payload.f", "v"),
            new ExecutionControlCommand.SignalNodeCommand("e", "n1", "approval", "approve", null),
            new ExecutionControlCommand.StopWorkflowCommand("e", "r", StopMode.DRAIN),
            new ExecutionControlCommand.RestartCommand("e", "e2"));
    for (final ExecutionControlCommand command : commands) {
      final var named =
          supervisor(
              "{\"rules\":[{\"id\":\"r\",\"deny\":{\"command\":\""
                  + command.getClass().getSimpleName()
                  + "\",\"if\":\"nodeId == 'n1'\"}}]}");
      final boolean hasNode =
          !(command instanceof ExecutionControlCommand.StopWorkflowCommand
              || command instanceof ExecutionControlCommand.RestartCommand);
      assertThat(review(named, command).allowed()).isEqualTo(!hasNode);
    }
  }

  @Test
  void constructor_invalidRules_failWithTheRuleId() {
    assertThatThrownBy(
            () ->
                supervisor("{\"rules\":[{\"when\":{},\"then\":{\"command\":\"PauseWorkflow\"}}]}"))
        .hasMessageContaining("needs an id");
    assertThatThrownBy(
            () -> supervisor("{\"rules\":[{\"id\":\" \",\"deny\":{\"command\":\"X\"}}]}"))
        .hasMessageContaining("needs an id");
    assertThatThrownBy(() -> supervisor("{\"rules\":[{\"id\":\"r\"}]}"))
        .hasMessageContaining("Rule 'r' needs either");
    assertThatThrownBy(
            () ->
                supervisor(
                    """
                    {"rules":[{"id":"r","when":{},"then":{"command":"PauseWorkflow"},
                      "deny":{"command":"X"}}]}
                    """))
        .hasMessageContaining("Rule 'r' needs either");
    assertThatThrownBy(() -> supervisor("{\"rules\":[{\"id\":\"r\",\"when\":{}}]}"))
        .hasMessageContaining("Rule 'r' needs both");
    assertThatThrownBy(() -> supervisor("{\"rules\":[{\"id\":\"r\",\"deny\":{}}]}"))
        .hasMessageContaining("must name the command");
    assertThatThrownBy(
            () -> supervisor("{\"rules\":[{\"id\":\"r\",\"deny\":{\"command\":\" \"}}]}"))
        .hasMessageContaining("must name the command");
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"r\",\"when\":{},\"then\":{\"command\":\"Explode\"}}]}"))
        .hasMessageContaining("Unknown command in rule: 'Explode'");
    assertThatThrownBy(() -> supervisor("{\"rules\":[{\"id\":\"r\",\"when\":{},\"then\":{}}]}"))
        .hasMessageContaining("Unknown command in rule");
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"bad\",\"when\":{\"if\":\"a"
                        + " ==\"},\"then\":{\"command\":\"PauseWorkflow\"}}]}"))
        .hasMessageContaining("Rule 'bad' has an invalid condition");
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"bad\",\"deny\":{\"command\":\"X\",\"if\":\"(\"}}]}"))
        .hasMessageContaining("Rule 'bad' has an invalid condition");
  }

  @Test
  void read_invalidOrUnknownJson_isRejected() {
    assertThatThrownBy(() -> RuleSupervisor.read("not json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid rules file");
    assertThatThrownBy(() -> RuleSupervisor.read("{\"rulez\":[]}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RuleSupervisor.read("{}").rules()).isEmpty();
  }

  @Test
  void constructor_fromFile_loadsTheRules(@TempDir final Path dir) throws IOException {
    final Path file = dir.resolve("rules.json");
    Files.writeString(
        file,
        """
        {"rules":[{"id":"r","when":{"status":"FAILURE"},"then":{"command":"PauseWorkflow"}}]}
        """);

    assertThat(run(new RuleSupervisor(file), node("e1", "FAILURE", null))).hasSize(1);
  }

  @Test
  void constructor_ruleWithOnlyThen_failsAsNeedingBoth() {
    assertThatThrownBy(
            () ->
                supervisor("{\"rules\":[{\"id\":\"r\",\"then\":{\"command\":\"PauseWorkflow\"}}]}"))
        .hasMessageContaining("Rule 'r' needs both");
  }

  @Test
  void supervise_windowAlreadyOver_neverReachesTheCount() {
    final var rules =
        supervisor(
            """
            {"rules":[{"id":"r","when":{"status":"FAILURE","count":2,"withinSeconds":-5},
              "then":{"command":"PauseWorkflow"}}]}
            """);
    final var failure = node("e1", "FAILURE", null);

    assertThat(run(rules, failure, failure, failure)).isEmpty();
  }
}
