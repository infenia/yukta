// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.ShortMethodName", "PMD.AvoidFieldNameMatchingMethodName"})
@NoArgsConstructor
class ControlReviewerTest {

  private static final ControlRequest REQUEST =
      new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null, null, null);

  @RequiredArgsConstructor
  private static final class Reviewer implements ExecutionSupervisor {
    private final String id;
    private final int order;
    private final Function<ControlRequest, Mono<Decision>> answer;
    private final List<String> asked;

    @Override
    public String id() {
      return id;
    }

    @Override
    public int order() {
      return order;
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }

    @Override
    public Mono<Decision> review(final ControlRequest request) {
      asked.add(id);
      return answer.apply(request);
    }
  }

  private static ExecutionSupervisor scoped(
      final String id, final SupervisorScope scope, final List<String> asked) {
    return new ExecutionSupervisor() {
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
        return Flux.empty();
      }

      @Override
      public Mono<Decision> review(final ControlRequest request) {
        asked.add(id);
        return Mono.just(Decision.deny("no"));
      }
    };
  }

  @Test
  void review_onlyAsksSupervisorsWhoseScopeCoversTheRun() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                scoped("sessionA", new SupervisorScope.Session("A"), asked),
                scoped("sessionB", new SupervisorScope.Session("B"), asked),
                scoped("wfOther", new SupervisorScope.Workflow("other", null), asked),
                scoped("wfMine", new SupervisorScope.Workflow("w1", "A"), asked)));
    final ControlRequest request =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null, "A", "w1");

    StepVerifier.create(reviewer.review(request))
        .expectError(ControlRejectedException.class)
        .verify();

    assertThat(asked).contains("sessionA").doesNotContain("sessionB", "wfOther");
  }

  @Test
  void review_limitsCommandIsReviewedByGlobalSupervisorsOnly() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                scoped("sessionA", new SupervisorScope.Session("A"), asked),
                scoped("global", new SupervisorScope.Global(), asked)));

    StepVerifier.create(
            reviewer.review(
                new ControlRequest(
                    new ExecutionControlCommand.SetLimitsCommand(1, 1), null, null, null)))
        .expectError(ControlRejectedException.class)
        .verify();

    assertThat(asked).containsExactly("global");
  }

  @Test
  void review_noSupervisors_allows() {
    StepVerifier.create(new ControlReviewer(providerOf()).review(REQUEST)).verifyComplete();
  }

  @Test
  void review_firstDenialWins_laterSupervisorsNotAsked() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Reviewer("late", 5, _ -> Mono.just(Decision.ALLOW), asked),
                new Reviewer("strict", 1, _ -> Mono.just(Decision.deny("nope")), asked),
                new Reviewer("never", 9, _ -> Mono.just(Decision.ALLOW), asked)));

    StepVerifier.create(reviewer.review(REQUEST))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ControlRejectedException.class)
                    .hasMessage("strict: nope"))
        .verify();
    assertThat(asked).containsExactly("strict");
  }

  @Test
  void review_supervisorThatFails_countsAsDenial() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Reviewer(
                    "broken",
                    0,
                    _ -> Mono.error(new IllegalStateException("x")),
                    new ArrayList<>())));

    StepVerifier.create(reviewer.review(REQUEST))
        .expectErrorMessage("broken: review failed")
        .verify();
  }

  @Test
  void review_allAllow_completes() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(new Reviewer("ok", 0, _ -> Mono.just(Decision.ALLOW), new ArrayList<>())));

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();
  }

  private static ControlReviewer hungReviewer() {
    return new ControlReviewer(
        providerOf(new Reviewer("hung", 0, _ -> Mono.never(), new ArrayList<>())),
        Duration.ofMillis(50));
  }

  @Test
  void review_hungSupervisor_refusesTheCommandAfterTheTimeout() {
    StepVerifier.create(hungReviewer().review(REQUEST))
        .expectErrorMessage("hung: review timed out")
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void review_hungSupervisor_stillLetsStopThrough() {
    StepVerifier.create(
            hungReviewer()
                .review(
                    new ControlRequest(
                        new ExecutionControlCommand.StopWorkflowCommand("e1", null, null),
                        null,
                        null,
                        null)))
        .verifyComplete();
    StepVerifier.create(
            hungReviewer()
                .review(
                    new ControlRequest(
                        new ExecutionControlCommand.StopNodeCommand("e1", "n1", false, null),
                        null,
                        null,
                        null)))
        .verifyComplete();
  }
}
