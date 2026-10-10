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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AvoidFieldNameMatchingMethodName"
})
@NoArgsConstructor
class ControlReviewerTest {

  private static final ControlRequest REQUEST =
      new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null);

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
}
