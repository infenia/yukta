// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.review.ControlRejectedException;
import com.infenia.yukta.service.review.ControlReviewer;
import com.infenia.yukta.service.review.ControlReviewerFixture;
import com.infenia.yukta.testing.Fixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Attaching and detaching supervisors at run time. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestShouldIncludeAssert"
})
class AttachedSupervisorTest {

  private final AttachedHarness harness = AttachedHarness.create();

  @AfterEach
  void tearDown() {
    harness.host().stop();
  }

  @Test
  void detachAllStopsEveryAttachedSupervisor() {
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Session("A"), false));
    harness.host().attach(harness.bound("b2", new SupervisorScope.Global(), false));

    harness.host().detachAll();

    assertThat(harness.host().detach("b1")).isFalse();
    assertThat(harness.host().detach("b2")).isFalse();
  }

  @Test
  void attachedSupervisorSeesOnlyItsScopeUntilDetached() {
    when(harness.dispatcher().dispatchOrdered(any())).thenReturn(Mono.empty());
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Session("A"), false));

    harness.publishUntilSeen("in-A", "A");
    assertThat(harness.host().status().getFirst().dryRun()).isFalse();
    harness
        .feed()
        .publish(
            new ExecutionEvent.WorkflowStatusChanged("in-B", "B", "w", "RUNNING", Instant.now()));
    assertThat(harness.host().detach("b1")).isTrue();
    harness
        .feed()
        .publish(
            new ExecutionEvent.WorkflowStatusChanged("after", "A", "w", "RUNNING", Instant.now()));

    assertThat(harness.seen()).doesNotContain("in-B", "after");
    assertThat(harness.host().detach("b1")).isFalse();
    verify(harness.dispatcher(), never())
        .dispatchOrdered(new ExecutionControlCommand.PauseWorkflowCommand("after"));
  }

  @Test
  void reattachingReplacesTheRunningSupervisorAndStatusShowsIt() {
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Session("A"), false));
    harness.host().attach(harness.bound("b1", new SupervisorScope.Workflow("w", "A"), true));

    assertThat(harness.host().status())
        .singleElement()
        .satisfies(
            info -> {
              assertThat(info.id()).isEqualTo("b1");
              assertThat(info.kind()).isEqualTo("SUPERVISOR");
              assertThat(info.scope()).isEqualTo("WORKFLOW");
              assertThat(info.scopeSessionId()).isEqualTo("A");
              assertThat(info.dryRun()).isTrue();
            });
  }

  @Test
  void detachingRemovesTheCommandCountersOfTheBinding() {
    when(harness.dispatcher().dispatchOrdered(any())).thenReturn(Mono.empty());
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Global(), false));
    harness.publishUntilSeen("r1", "A");
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        harness
                            .meters()
                            .find("yukta.supervisor.commands")
                            .tag("id", "b1")
                            .counters())
                    .isNotEmpty());

    harness.host().detach("b1");

    assertThat(harness.meters().find("yukta.supervisor.commands").tag("id", "b1").counters())
        .isEmpty();
  }

  @Test
  void attachedDryRunSupervisorOnlyRecords() {
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Global(), true));

    harness.publishUntilSeen("e1", "A");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(harness.host().status().getFirst().recorded()).isPositive());
    verify(harness.dispatcher(), never()).dispatchOrdered(any());
  }

  @Test
  void theServerWideDryRunAppliesToAttachedSupervisorsToo() {
    final SupervisorHost recordingHost =
        SupervisorHostFixture.create(
            providerOf(),
            harness.feed(),
            harness.dispatcher(),
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            true,
            10,
            1000);
    try {
      recordingHost.attach(harness.bound("b1", new SupervisorScope.Global(), false));

      assertThat(recordingHost.status().getFirst().dryRun()).isTrue();
    } finally {
      recordingHost.stop();
    }
  }

  @Test
  void stopEndsAttachedSupervisorsToo() {
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Global(), false));
    harness.host().stop();

    assertThat(harness.host().detach("b1")).isTrue();
  }

  @Test
  void theReviewerAsksAttachedSupervisorsWhoseScopeCoversTheRun() {
    final ControlReviewer reviewer = ControlReviewerFixture.create(providerOf());
    reviewer.setAttachedSupervisors(harness.attached());
    harness.attached().put(harness.bound("b1", new SupervisorScope.Session("A"), false));
    harness.attached().put(harness.bound("b2", new SupervisorScope.Session("B"), false));

    final ControlRequest inA =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "A", "w");
    final ControlRequest inC =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "C", "w");

    StepVerifier.create(reviewer.review(inA)).expectError(ControlRejectedException.class).verify();
    StepVerifier.create(reviewer.review(inC)).verifyComplete();
    assertThat(harness.attached().remove("b1")).isNotNull();
    StepVerifier.create(reviewer.review(inA)).verifyComplete();
  }

  @Test
  void boundSupervisorAnswersToItsBindingAndDoesWhatTheDelegateDoes() {
    final BoundSupervisor supervisor = harness.bound("b1", new SupervisorScope.Session("A"), false);

    assertThat(supervisor.id()).isEqualTo("b1");
    assertThat(supervisor.order()).isZero();
    assertThat(supervisor.scope()).isEqualTo(new SupervisorScope.Session("A"));
    StepVerifier.create(
            supervisor.review(
                new ControlRequest(
                    new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "A", "w")))
        .expectNext(Decision.deny("no"))
        .verifyComplete();
  }

  @Test
  void decisionsRecordWhatBecameOfEachCommandNewestFirst() {
    when(harness.dispatcher().dispatchOrdered(any())).thenReturn(Mono.empty());
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("live", new SupervisorScope.Global(), false));
    harness.host().attach(harness.bound("dry", new SupervisorScope.Global(), true));

    harness.publishUntilSeen("e1", "A");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(harness.host().decisions("live", 10))
                  .extracting(SupervisorDecision::outcome)
                  .contains("applied");
              assertThat(harness.host().decisions("dry", 10))
                  .extracting(SupervisorDecision::outcome)
                  .contains("dry-run");
            });
    assertThat(harness.host().decisions(null, 1)).hasSize(1);
    assertThat(harness.host().decisions("nobody", 10)).isEmpty();
    assertThat(harness.host().decisions(null, 0)).isEmpty();
  }

  @Test
  void onlyTheNewestFiveHundredDecisionsAreKept() {
    final ExecutionSupervisor chatty =
        new ExecutionSupervisor() {
          @Override
          public String id() {
            return "chatty";
          }

          @Override
          public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
            return events
                .take(1)
                .flatMap(
                    e ->
                        Flux.range(0, 600)
                            .map(i -> new ExecutionControlCommand.PauseWorkflowCommand("e" + i)));
          }
        };
    harness.host().setAttachedSupervisors(harness.attached());
    harness
        .host()
        .attach(
            Fixtures.boundSupervisor(
                new SupervisorAttachment("chatty", "p", new SupervisorScope.Global(), true),
                chatty,
                true));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              harness
                  .feed()
                  .publish(
                      new ExecutionEvent.WorkflowStatusChanged(
                          "x", "A", "w", "RUNNING", Instant.now()));
              assertThat(harness.host().decisions("chatty", 1000)).hasSize(500);
            });
    assertThat(harness.host().decisions("chatty", 1000).getFirst().executionId()).isEqualTo("e599");
  }

  @Test
  void rejectedCommandsAreRecordedWithTheirReason() {
    when(harness.dispatcher().dispatchOrdered(any()))
        .thenReturn(Mono.error(new ControlRejectedException("no")));
    harness.host().setAttachedSupervisors(harness.attached());
    harness.host().attach(harness.bound("b1", new SupervisorScope.Global(), false));

    harness.publishUntilSeen("e1", "A");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(harness.host().decisions("b1", 10))
                    .anySatisfy(
                        d -> {
                          assertThat(d.outcome()).isEqualTo("rejected");
                          assertThat(d.reason()).contains("no");
                          assertThat(d.command()).isEqualTo("PauseWorkflowCommand");
                        }));
  }
}
