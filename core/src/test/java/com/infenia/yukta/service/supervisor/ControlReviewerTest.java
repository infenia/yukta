// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.ShortMethodName", "PMD.AvoidFieldNameMatchingMethodName"})
@NoArgsConstructor
class ControlReviewerTest {

  private static final String SESSION_A = "sessionA";

  /* default */ static final ControlRequest REQUEST =
      new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null, null, null);

  @RequiredArgsConstructor
  /* default */ static final class Reviewer implements ExecutionSupervisor {
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

  @RequiredArgsConstructor
  /* default */ static final class Scoped implements ExecutionSupervisor {
    private final String id;
    private final SupervisorScope scope;
    private final List<String> asked;

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
  }

  /** A control plane with a class name, as real ones have. */
  /* default */ static final class NamedPlane implements ControlPlanePlugin {
    @Override
    public String id() {
      return "plane";
    }

    @Override
    public int order() {
      return 5;
    }

    @Override
    public Mono<ControlDecision> decide(final ExecutionEvent event) {
      return Mono.just(ControlDecision.none(ControlDecision.Source.MODEL));
    }
  }

  @Test
  void settingsBuildReviewerThatStillRefusesWhenNotDryRun() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(new Scoped(SESSION_A, new SupervisorScope.Session("A"), new ArrayList<>())),
            YuktaSettings.bind(Map.of("yukta.supervisor.review-timeout", "2s")));
    final ControlRequest request =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null, "A", "w1");

    StepVerifier.create(reviewer.review(request))
        .expectError(ControlRejectedException.class)
        .verify();
  }

  @Test
  void dryRunFromSettingsLetsRefusedRunThrough() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(new Scoped(SESSION_A, new SupervisorScope.Session("A"), new ArrayList<>())));
    reviewer.setDryRun(
        new StaticListableBeanFactory().getBeanProvider(SupervisorHost.class),
        YuktaSettings.bind(Map.of("yukta.supervisor.dry-run", "true")));
    final ControlRequest request =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null, "A", "w1");

    StepVerifier.create(reviewer.review(request)).verifyComplete();
  }

  @Test
  void review_onlyAsksSupervisorsWhoseScopeCoversTheRun() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Scoped(SESSION_A, new SupervisorScope.Session("A"), asked),
                new Scoped("sessionB", new SupervisorScope.Session("B"), asked),
                new Scoped("wfOther", new SupervisorScope.Workflow("other", null), asked),
                new Scoped("wfMine", new SupervisorScope.Workflow("w1", "A"), asked)));
    final ControlRequest request =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e1"), null, "A", "w1");

    StepVerifier.create(reviewer.review(request))
        .expectError(ControlRejectedException.class)
        .verify();

    assertThat(asked).contains(SESSION_A).doesNotContain("sessionB", "wfOther");
  }

  @Test
  void review_limitsCommandIsReviewedByGlobalSupervisorsOnly() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Scoped(SESSION_A, new SupervisorScope.Session("A"), asked),
                new Scoped("global", new SupervisorScope.Global(), asked)));

    StepVerifier.create(
            reviewer.review(
                new ControlRequest(
                    new ExecutionControlCommand.SetLimitsCommand(1, 1), null, null, null)))
        .expectError(ControlRejectedException.class)
        .verify();

    assertThat(asked).containsExactly("global");
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
    StepVerifier.create(new ControlReviewer(providerOf()).review(REQUEST)).verifyComplete();

    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(new Reviewer("ok", 0, _ -> Mono.just(Decision.ALLOW), new ArrayList<>())));

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();
  }

  @Test
  void review_hungSupervisor_refusesTheCommandAfterTheTimeout() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(new Reviewer("hung", 0, _ -> Mono.never(), new ArrayList<>())),
            Duration.ofMillis(50));

    StepVerifier.create(reviewer.review(REQUEST))
        .expectErrorMessage("hung: review timed out")
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void review_hungSupervisor_stillLetsStopThrough() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(new Reviewer("hung", 0, _ -> Mono.never(), new ArrayList<>())),
            Duration.ofMillis(50));

    StepVerifier.create(
            reviewer.review(
                new ControlRequest(
                    new ExecutionControlCommand.StopWorkflowCommand("e1", null, null),
                    null,
                    null,
                    null)))
        .verifyComplete();
    StepVerifier.create(
            reviewer.review(
                new ControlRequest(
                    new ExecutionControlCommand.StopNodeCommand("e1", "n1", false, null),
                    null,
                    null,
                    null)))
        .verifyComplete();
  }

  @Test
  void review_failingSupervisor_refusesStopToo() {
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Reviewer(
                    "broken",
                    0,
                    _ -> Mono.error(new IllegalStateException("x")),
                    new ArrayList<>())));

    StepVerifier.create(
            reviewer.review(
                new ControlRequest(
                    new ExecutionControlCommand.StopWorkflowCommand("e1", null, null),
                    null,
                    null,
                    null)))
        .expectErrorMessage("broken: review failed")
        .verify();
  }
}
