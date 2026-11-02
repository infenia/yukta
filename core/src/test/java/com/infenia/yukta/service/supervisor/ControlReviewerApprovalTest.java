// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests how the verdicts of several supervisors combine, with a need for approval. */
@SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "PMD.TooManyMethods"})
@NoArgsConstructor
class ControlReviewerApprovalTest {

  private static final ControlRequest PAUSE =
      new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "s", "w");

  private static final ControlRequest STOP =
      new ControlRequest(
          new ExecutionControlCommand.StopWorkflowCommand("e", null, null), null, "s", "w");

  private static final ControlRequest STOP_NODE =
      new ControlRequest(
          new ExecutionControlCommand.StopNodeCommand("e", "n", false, null), null, "s", "w");

  private static final ApprovalNeed LOW_ROUTE = new ApprovalNeed(InputRisk.LOW, "ops", null);

  private record Fake(String id, int order, Mono<Decision> answer) implements ExecutionSupervisor {

    /* default */ Fake(final String id, final int order, final Decision decision) {
      this(id, order, Mono.just(decision));
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }

    @Override
    public Mono<Decision> review(final ControlRequest request) {
      return answer;
    }
  }

  private static Mono<ReviewVerdict> verdict(
      final ControlRequest request, final Set<String> satisfied, final ExecutionSupervisor... all) {
    return new ControlReviewer(providerOf(all), Duration.ofMillis(200))
        .verdict(request, satisfied)
        .contextWrite(context -> context.put(ControlActor.KIND_KEY, ControlActor.Kind.SUPERVISOR));
  }

  private static Fake need(final String id, final int order, final ApprovalNeed need) {
    return new Fake(id, order, Decision.requireApproval(id + " wants it", need));
  }

  @Test
  void noSupervisorsAllow() {
    StepVerifier.create(verdict(PAUSE, Set.of()))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
  }

  @Test
  void denialWinsOverNeedForApproval() {
    StepVerifier.create(
            verdict(
                PAUSE,
                Set.of(),
                need("a", 1, LOW_ROUTE),
                new Fake("b", 2, Decision.deny("closed"))))
        .expectNext(new ReviewVerdict.Denied("b: closed"))
        .verifyComplete();
  }

  @Test
  void denialAfterTheNeedEndsTheReviewAndEarlierDenialStopsLater() {
    StepVerifier.create(
            verdict(
                PAUSE, Set.of(), new Fake("a", 1, Decision.deny("first")), need("b", 2, LOW_ROUTE)))
        .expectNext(new ReviewVerdict.Denied("a: first"))
        .verifyComplete();
  }

  @Test
  void severalNeedsMergeIntoOneRequest() {
    StepVerifier.create(
            verdict(
                PAUSE,
                Set.of(),
                need("a", 1, new ApprovalNeed(InputRisk.LOW, "ops", Duration.ofMinutes(5))),
                need("b", 2, new ApprovalNeed(InputRisk.HIGH, "other", Duration.ofMinutes(2))),
                new Fake(
                    "c", 3, Decision.requireApproval(null, new ApprovalNeed(null, null, null))),
                new Fake("d", 4, Decision.ALLOW)))
        .assertNext(
            verdict -> {
              final ReviewVerdict.NeedsApproval needs = (ReviewVerdict.NeedsApproval) verdict;
              assertThat(needs.askedBy()).containsExactly("a", "b", "c");
              assertThat(needs.need())
                  .isEqualTo(new ApprovalNeed(InputRisk.HIGH, "ops", Duration.ofMinutes(2)));
              assertThat(needs.reason())
                  .isEqualTo("a: a wants it; b: b wants it; c: approval required");
            })
        .verifyComplete();
  }

  @Test
  void lowerRiskAfterHigherOneKeepsTheHigher() {
    StepVerifier.create(
            verdict(
                PAUSE,
                Set.of(),
                need("a", 1, new ApprovalNeed(InputRisk.HIGH, null, Duration.ofMinutes(1))),
                need("b", 2, new ApprovalNeed(InputRisk.LOW, null, Duration.ofMinutes(9)))))
        .assertNext(
            verdict ->
                assertThat(((ReviewVerdict.NeedsApproval) verdict).need())
                    .isEqualTo(new ApprovalNeed(InputRisk.HIGH, null, Duration.ofMinutes(1))))
        .verifyComplete();
  }

  @Test
  void satisfiedSupervisorNeedsNoMoreApprovalButStillDenies() {
    StepVerifier.create(verdict(PAUSE, Set.of("a"), need("a", 1, LOW_ROUTE)))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
    StepVerifier.create(
            verdict(PAUSE, Set.of("a"), need("a", 1, LOW_ROUTE), need("b", 2, LOW_ROUTE)))
        .assertNext(
            verdict ->
                assertThat(((ReviewVerdict.NeedsApproval) verdict).askedBy()).containsExactly("b"))
        .verifyComplete();
    StepVerifier.create(
            verdict(
                PAUSE, Set.of("a"), new Fake("a", 1, Decision.deny("no")), need("b", 2, LOW_ROUTE)))
        .expectNext(new ReviewVerdict.Denied("a: no"))
        .verifyComplete();
  }

  @Test
  void stopFromPersonIsNeverParked() {
    for (final ControlRequest stop : new ControlRequest[] {STOP, STOP_NODE}) {
      final Mono<ReviewVerdict> byPerson =
          new ControlReviewer(providerOf(need("a", 1, LOW_ROUTE)), Duration.ofMillis(200))
              .verdict(stop, Set.of());
      StepVerifier.create(byPerson).expectNext(ReviewVerdict.ALLOWED).verifyComplete();
      StepVerifier.create(
              byPerson.contextWrite(
                  context -> context.put(ControlActor.KIND_KEY, ControlActor.Kind.SUPERVISOR)))
          .expectNextMatches(ReviewVerdict.NeedsApproval.class::isInstance)
          .verifyComplete();
    }
  }

  @Test
  void stopFromPersonIsStillDeniedByDenial() {
    StepVerifier.create(
            new ControlReviewer(
                    providerOf(new Fake("a", 1, Decision.deny("no")), need("b", 2, LOW_ROUTE)),
                    Duration.ofMillis(200))
                .verdict(STOP, Set.of()))
        .expectNext(new ReviewVerdict.Denied("a: no"))
        .verifyComplete();
  }

  @Test
  void personsOtherCommandCanBeParked() {
    StepVerifier.create(
            new ControlReviewer(providerOf(need("a", 1, LOW_ROUTE)), Duration.ofMillis(200))
                .verdict(PAUSE, Set.of()))
        .expectNextMatches(ReviewVerdict.NeedsApproval.class::isInstance)
        .verifyComplete();
  }

  @Test
  void failureOrTimeoutBehavesAsBefore() {
    StepVerifier.create(
            verdict(PAUSE, Set.of(), new Fake("a", 1, Mono.error(new IllegalStateException("x")))))
        .expectNext(new ReviewVerdict.Denied("a: review failed"))
        .verifyComplete();
    StepVerifier.create(verdict(PAUSE, Set.of(), new Fake("a", 1, Mono.never())))
        .expectNext(new ReviewVerdict.Denied("a: review timed out"))
        .verifyComplete();
    StepVerifier.create(verdict(STOP, Set.of(), new Fake("a", 1, Mono.never())))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
  }

  @Test
  void reviewTreatsNeedAsRefusal() {
    StepVerifier.create(
            new ControlReviewer(providerOf(need("a", 1, LOW_ROUTE)), Duration.ofMillis(200))
                .review(PAUSE))
        .expectErrorMatches(
            error ->
                error instanceof ControlRejectedException && error.getMessage().contains("a wants"))
        .verify();
    StepVerifier.create(
            new ControlReviewer(
                    providerOf(new Fake("a", 1, Decision.deny("no"))), Duration.ofMillis(200))
                .review(PAUSE))
        .expectErrorMatches(error -> error.getMessage().contains("a: no"))
        .verify();
    StepVerifier.create(
            new ControlReviewer(
                    providerOf(new Fake("a", 1, Decision.ALLOW)), Duration.ofMillis(200))
                .review(PAUSE))
        .verifyComplete();
  }

  @Test
  void dryRunNeedIsRecordedNotEnforced() {
    final ControlReviewer reviewer =
        new ControlReviewer(providerOf(need("a", 1, LOW_ROUTE)), Duration.ofMillis(200));
    final SupervisorHost host = mock(SupervisorHost.class);
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("host", host);
    reviewer.setDryRun(beans.getBeanProvider(SupervisorHost.class), true);
    StepVerifier.create(reviewer.verdict(PAUSE, Set.of()))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
    verify(host).wouldHaveRefused("a", PAUSE.command(), "would need approval: a wants it");
  }
}
