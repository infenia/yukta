// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Tests that the dispatcher tells reviewers and the feed which node a command acts on. */
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.TooManyStaticImports"})
@NoArgsConstructor
class DirectiveDispatcherTargetTest {

  private final ExecutionControlStore registry = new InMemoryExecutionControlStore();

  private final InputRequestStore requests = new InMemoryInputRequestStore(100);

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final List<ControlRequest> reviewed = new CopyOnWriteArrayList<>();

  private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();

  private DirectiveDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    registry.save(
        new ExecutionControl(
            "session",
            "workflow",
            "exec",
            null,
            Map.of(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            new AtomicBoolean()));
    final CommandHandler handler = mock(CommandHandler.class);
    when(handler.handle(any())).thenReturn(Mono.empty());
    final ExecutionSupervisor supervisor = mock(ExecutionSupervisor.class);
    when(supervisor.scope()).thenReturn(new SupervisorScope.Global());
    when(supervisor.id()).thenReturn("guard");
    when(supervisor.review(any()))
        .thenAnswer(
            call -> {
              reviewed.add(call.getArgument(0));
              return Mono.just(Decision.ALLOW);
            });
    dispatcher =
        new DirectiveDispatcher(
            handler,
            registry,
            mock(ControlBusService.class),
            new ExecutionArchive(),
            new InMemoryExecutionLogStore(),
            new ControlReviewer(providerOf(supervisor)),
            feed);
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("requests", requests);
    dispatcher.setInputRequests(beans.getBeanProvider(InputRequestStore.class));
    feed.events().subscribe(events::add);
  }

  private static InputRequest request(final String id) {
    return InputTestSupport.request(
        id,
        InputTestSupport.spec(
            "k",
            InputKind.APPROVAL,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            List.of(),
            null,
            Map.of()));
  }

  @Test
  void nodeCommandNamesItsNodeToReviewersAndInItsOutcome() {
    StepVerifier.create(
            dispatcher.dispatchOrdered(new ExecutionControlCommand.PauseNodeCommand("exec", "n1")))
        .verifyComplete();

    assertThat(reviewed)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.sessionId()).isEqualTo("session");
              assertThat(request.workflowId()).isEqualTo("workflow");
              assertThat(request.nodeId()).isEqualTo("n1");
            });
    Awaitility.await().until(() -> !events.isEmpty());
    assertThat(events.getFirst().nodeId()).isEqualTo("n1");
  }

  @Test
  void workflowCommandNamesNoNode() {
    StepVerifier.create(
            dispatcher.dispatchOrdered(new ExecutionControlCommand.PauseWorkflowCommand("exec")))
        .verifyComplete();

    assertThat(reviewed).singleElement().satisfies(r -> assertThat(r.nodeId()).isNull());
  }

  @Test
  void inputCommandsTakeTheNodeOfTheirRequest() {
    StepVerifier.create(requests.createIfAbsent(request("r1"))).expectNextCount(1).verifyComplete();

    StepVerifier.create(
            dispatcher.target(
                new ExecutionControlCommand.AnswerInputCommand(
                    "exec", "r1", null, "ok", null, Map.of(), null, null)))
        .expectNext(new CommandTarget("session", "workflow", "node"))
        .verifyComplete();
    StepVerifier.create(
            dispatcher.target(
                new ExecutionControlCommand.EscalateInputCommand("exec", "r1", "why", null)))
        .expectNext(new CommandTarget("session", "workflow", "node"))
        .verifyComplete();
  }

  @Test
  void inputCommandOfAnUnknownRequestOrAnotherRunNamesNoNode() {
    StepVerifier.create(requests.createIfAbsent(request("r1"))).expectNextCount(1).verifyComplete();

    StepVerifier.create(
            dispatcher.target(
                new ExecutionControlCommand.EscalateInputCommand("exec", "missing", "why", null)))
        .expectNext(new CommandTarget("session", "workflow", null))
        .verifyComplete();
    StepVerifier.create(
            dispatcher.target(
                new ExecutionControlCommand.EscalateInputCommand("other", "r1", "why", null)))
        .expectNext(new CommandTarget(null, null, null))
        .verifyComplete();
  }

  @Test
  void withoutAStoreTheInputCommandNamesNoNode() {
    dispatcher.setInputRequests(
        new StaticListableBeanFactory().getBeanProvider(InputRequestStore.class));

    StepVerifier.create(
            dispatcher.target(
                new ExecutionControlCommand.EscalateInputCommand("exec", "r1", "why", null)))
        .expectNext(new CommandTarget("session", "workflow", null))
        .verifyComplete();
  }

  @Test
  void schedulerCommandBelongsToNoRun() {
    StepVerifier.create(dispatcher.target(new ExecutionControlCommand.SetLimitsCommand(1, 1)))
        .expectNext(new CommandTarget(null, null, null))
        .verifyComplete();
  }
}
