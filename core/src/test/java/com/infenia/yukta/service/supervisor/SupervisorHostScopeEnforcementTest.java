// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetLimitsCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.control.directive.CommandTarget;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests that a supervisor may act only within its scope. */
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.ExcessiveImports",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortMethodName"
})
@NoArgsConstructor
class SupervisorHostScopeEnforcementTest {

  /** A run of session {@code s}, workflow {@code w}. */
  private static final String MINE = "mine";

  /** A run of session {@code other}, workflow {@code w}. */
  private static final String OTHER = "other";

  /** A run whose target cannot be read. */
  private static final String BROKEN = "broken";

  private static final String OUT_OF_SCOPE = "out-of-scope";

  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private SupervisorHost host;

  @RequiredArgsConstructor
  private static final class Sender implements ExecutionSupervisor {
    private final String id;
    private final SupervisorScope scope;
    private final List<ExecutionControlCommand> commands;

    @Override
    public String id() {
      return id;
    }

    @Override
    public SupervisorScope scope() {
      return scope;
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.fromIterable(commands);
    }
  }

  @BeforeEach
  void setUp() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    when(dispatcher.target(any()))
        .thenAnswer(
            call -> {
              final ExecutionControlCommand command = call.getArgument(0);
              return switch (command.executionId()) {
                case MINE -> Mono.just(new CommandTarget("s", "w", command.nodeId()));
                case OTHER -> Mono.just(new CommandTarget(OTHER, "w", command.nodeId()));
                case BROKEN -> Mono.error(new IllegalStateException("store down"));
                default -> Mono.just(new CommandTarget(null, null, null));
              };
            });
  }

  @AfterEach
  void tearDown() {
    if (host != null) {
      host.stop();
    }
  }

  private List<SupervisorDecision> run(
      final ExecutionSupervisor supervisor, final boolean dryRun, final int expected) {
    host =
        new SupervisorHost(
            providerOf(supervisor),
            new ExecutionEventFeed(),
            dispatcher,
            log,
            meters,
            dryRun,
            100,
            1_000);
    host.start();
    await()
        .atMost(3, TimeUnit.SECONDS)
        .until(() -> host.decisions(supervisor.id(), 100).size() == expected);
    return host.decisions(supervisor.id(), 100).reversed();
  }

  private double counted(final String id, final String outcome) {
    return meters.counter("yukta.supervisor.commands", "id", id, "outcome", outcome).count();
  }

  @Test
  void sectionSupervisorActsOnlyOnItsNodesOfItsRuns() {
    final List<SupervisorDecision> decisions =
        run(
            new Sender(
                "build",
                SupervisorScope.parse("workflow:s/w//build.*"),
                List.of(
                    new PauseNodeCommand(MINE, "build.compile"),
                    new PauseNodeCommand(MINE, "deploy.prod"),
                    new PauseWorkflowCommand(MINE),
                    new PauseNodeCommand(OTHER, "build.compile"),
                    new SetLimitsCommand(1, 1))),
            false,
            5);

    assertThat(decisions)
        .extracting(SupervisorDecision::outcome)
        .containsExactly("applied", OUT_OF_SCOPE, OUT_OF_SCOPE, OUT_OF_SCOPE, OUT_OF_SCOPE);
    assertThat(decisions.get(1).reason()).isEqualTo("target outside scope workflow:s/w//build.*");
    verify(dispatcher).dispatchOrdered(new PauseNodeCommand(MINE, "build.compile"));
    verify(dispatcher, never()).dispatchOrdered(new PauseWorkflowCommand(MINE));
    assertThat(counted("build", OUT_OF_SCOPE)).isEqualTo(4);
    assertThat(log.readAll())
        .filteredOn(entry -> entry.type() == ExecutionLogEvent.Type.CONTROL_REJECTED)
        .hasSize(4);
  }

  @Test
  void sessionSupervisorCannotStopRunOfAnotherSession() {
    final List<SupervisorDecision> decisions =
        run(
            new Sender(
                "session",
                new SupervisorScope.Session("s"),
                List.of(
                    new StopWorkflowCommand(OTHER, "no"),
                    new StopWorkflowCommand(MINE, "yes"),
                    new SetLimitsCommand(1, 1))),
            false,
            3);

    assertThat(decisions)
        .extracting(SupervisorDecision::outcome)
        .containsExactly(OUT_OF_SCOPE, "applied", OUT_OF_SCOPE);
    verify(dispatcher, never()).dispatchOrdered(new StopWorkflowCommand(OTHER, "no"));
  }

  @Test
  void globalSupervisorActsAnywhereWithoutLookup() {
    final List<SupervisorDecision> decisions =
        run(
            new Sender(
                "global",
                new SupervisorScope.Global(),
                List.of(new StopWorkflowCommand(OTHER, "x"), new SetLimitsCommand(1, 1))),
            false,
            2);

    assertThat(decisions).extracting(SupervisorDecision::outcome).containsOnly("applied");
    verify(dispatcher, never()).target(any());
  }

  @Test
  void dryRunRecordsTheSameScopeDecision() {
    final List<SupervisorDecision> decisions =
        run(
            new Sender(
                "dry",
                SupervisorScope.parse("workflow:s/w"),
                List.of(new PauseWorkflowCommand(MINE), new PauseWorkflowCommand(OTHER))),
            true,
            2);

    assertThat(decisions)
        .extracting(SupervisorDecision::outcome)
        .containsExactly("dry-run", OUT_OF_SCOPE);
    verify(dispatcher, never()).dispatchOrdered(any());
  }

  @Test
  void targetThatCannotBeReadIsOutOfScope() {
    final List<SupervisorDecision> decisions =
        run(
            new Sender(
                "broken",
                new SupervisorScope.Session("s"),
                List.of(new PauseWorkflowCommand(BROKEN))),
            false,
            1);

    assertThat(decisions).extracting(SupervisorDecision::outcome).containsExactly(OUT_OF_SCOPE);
  }
}
