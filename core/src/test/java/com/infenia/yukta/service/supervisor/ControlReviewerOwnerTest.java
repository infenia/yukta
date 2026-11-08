// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Production review: the owner only, and only for what people send. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class ControlReviewerOwnerTest {

  private static final String PAUSE = "PauseWorkflowCommand";
  private static final String NODE_PAUSE = "PauseNodeCommand";

  private static final ControlRequest RUN_COMMAND =
      new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "s", "w");

  private static final ControlRequest NODE_COMMAND =
      new ControlRequest(
          new ExecutionControlCommand.PauseNodeCommand("e", "build.a"), null, "s", "w");

  private static final ControlRequest SERVER_COMMAND =
      new ControlRequest(new ExecutionControlCommand.SetLimitsCommand(1, 1), null, null, null);

  private final AttachedSupervisors attached = new AttachedSupervisors();

  private ControlReviewer reviewer(final boolean defaultEnabled) {
    final ControlReviewer reviewer =
        new ControlReviewer(providerOf(), java.time.Duration.ofSeconds(2));
    reviewer.setAttachedSupervisors(attached);
    reviewer.setDryRun(
        new StaticListableBeanFactory().getBeanProvider(SupervisorHost.class),
        YuktaSettings.bind(
            Map.of("yukta.supervisor.default.enabled", String.valueOf(defaultEnabled))));
    return reviewer;
  }

  private ExecutionSupervisor answering(final Decision decision) {
    final ExecutionSupervisor delegate = Mockito.mock(ExecutionSupervisor.class);
    Mockito.when(delegate.review(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Mono.just(decision));
    return delegate;
  }

  private ExecutionSupervisor own(
      final String id,
      final SupervisorScope scope,
      final ExecutionSupervisor delegate,
      final String... operatorAccess) {
    final ExecutionSupervisor bound =
        new BoundSupervisor(
            new SupervisorAttachment(id, id + "-sup", scope, true),
            delegate,
            false,
            new SupervisorCapabilities(null, false, InputRisk.LOW, Set.of(operatorAccess)),
            false);
    attached.put((BoundSupervisor) bound);
    return delegate;
  }

  private static Context contextOf(final ControlActor.Channel channel) {
    return Context.of(ControlActor.CHANNEL_KEY, channel.name());
  }

  private static Mono<ReviewVerdict> verdict(
      final ControlReviewer reviewer, final ControlRequest request, final ControlActor.Channel c) {
    return reviewer.verdict(request, Set.of()).contextWrite(contextOf(c));
  }

  @Test
  void nothingOwnedGoesToTheDefaultSupervisor() {
    StepVerifier.create(verdict(reviewer(true), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
  }

  @Test
  void nothingOwnedWithTheDefaultOffIsRefusedToPeople() {
    StepVerifier.create(verdict(reviewer(false), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(
            v ->
                assertThat(v)
                    .isInstanceOfSatisfying(
                        ReviewVerdict.Controlled.class, c -> assertThat(c.supervisorId()).isNull()))
        .verifyComplete();
  }

  @Test
  void anOwnerThatListsNoOperatorAccessControlsTheUnit() {
    own("a", new SupervisorScope.Workflow("w", "s"), answering(Decision.ALLOW));

    StepVerifier.create(verdict(reviewer(true), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(
            v ->
                assertThat(v)
                    .isInstanceOfSatisfying(
                        ReviewVerdict.Controlled.class,
                        c -> {
                          assertThat(c.supervisorId()).isEqualTo("a-sup");
                          assertThat(c.reason()).contains("a-sup controls this");
                        }))
        .verifyComplete();
  }

  @Test
  void reviewTurnsControlledUnitsIntoConflictsPeopleSee() {
    own("a", new SupervisorScope.Workflow("w", "s"), answering(Decision.ALLOW));

    StepVerifier.create(
            reviewer(true)
                .review(RUN_COMMAND)
                .contextWrite(contextOf(ControlActor.Channel.OPERATOR)))
        .expectError(ControlledBySupervisorException.class)
        .verify();
  }

  @Test
  void anOwnerThatListsTheCommandReviewsItAndItsAnswerApplies() {
    final ExecutionSupervisor refuser = answering(Decision.deny("closed"));
    own("a", new SupervisorScope.Workflow("w", "s"), refuser, PAUSE);

    StepVerifier.create(verdict(reviewer(true), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.Denied.class))
        .verifyComplete();
    verify(refuser).review(RUN_COMMAND);
  }

  @Test
  void anOwnerThatAllowsLetsTheCommandThrough() {
    own("a", new SupervisorScope.Workflow("w", "s"), answering(Decision.ALLOW), PAUSE);

    StepVerifier.create(verdict(reviewer(true), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
  }

  @Test
  void anEnginesAndTheSystemsCommandsAreNotReviewed() {
    final ExecutionSupervisor owner = answering(Decision.deny("closed"));
    own("a", new SupervisorScope.Workflow("w", "s"), owner);
    final ControlReviewer reviewer = reviewer(false);

    StepVerifier.create(verdict(reviewer, RUN_COMMAND, ControlActor.Channel.ENGINE))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
    StepVerifier.create(verdict(reviewer, RUN_COMMAND, ControlActor.Channel.SYSTEM))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
    StepVerifier.create(reviewer.verdict(RUN_COMMAND, Set.of()))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
    verify(owner, never()).review(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void globalScopeAndBeanSupervisorsOwnNothing() {
    own("g", new SupervisorScope.Global(), answering(Decision.deny("no")), PAUSE);
    final ExecutionSupervisor bean = answering(Decision.deny("no"));
    final ControlReviewer reviewer =
        new ControlReviewer(providerOf(bean), java.time.Duration.ofSeconds(2));
    reviewer.setAttachedSupervisors(attached);

    StepVerifier.create(verdict(reviewer, RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
    verify(bean, never()).review(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void runLevelAndNodeCommandsGoToTheirOwnOwnersWhenWorkflowIsSplit() {
    final ExecutionSupervisor runOwner = answering(Decision.deny("run owner"));
    final ExecutionSupervisor nodeOwner = answering(Decision.deny("node owner"));
    own("r", new SupervisorScope.Run("w", "s"), runOwner, PAUSE);
    own("n", new SupervisorScope.Section("w", "s", Set.of("build.*")), nodeOwner, NODE_PAUSE);
    final ControlReviewer reviewer = reviewer(true);

    StepVerifier.create(verdict(reviewer, RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.Denied.class))
        .verifyComplete();
    StepVerifier.create(verdict(reviewer, NODE_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.Denied.class))
        .verifyComplete();
    verify(runOwner).review(RUN_COMMAND);
    verify(nodeOwner).review(NODE_COMMAND);
    verify(runOwner, never()).review(NODE_COMMAND);
    verify(nodeOwner, never()).review(RUN_COMMAND);
  }

  @Test
  void nodeOwnerDoesNotOwnTheRunLevel() {
    own("n", new SupervisorScope.Section("w", "s", Set.of("build.*")), answering(Decision.ALLOW));

    StepVerifier.create(verdict(reviewer(true), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .expectNext(ReviewVerdict.ALLOWED)
        .verifyComplete();
  }

  @Test
  void theServerOwnerAnswersSchedulerCommands() {
    own(
        "srv",
        new SupervisorScope.Server(),
        answering(Decision.deny("capacity")),
        "SetLimitsCommand");

    StepVerifier.create(verdict(reviewer(true), SERVER_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.Denied.class))
        .verifyComplete();
  }

  @Test
  void twoOwnersFromLegacyDataLetTheLowestIdAnswer() {
    final ExecutionSupervisor first = answering(Decision.deny("first"));
    final ExecutionSupervisor second = answering(Decision.deny("second"));
    own("b", new SupervisorScope.Workflow("w", "s"), second, PAUSE);
    own("a", new SupervisorScope.Workflow("w", "s"), first, PAUSE);

    StepVerifier.create(verdict(reviewer(true), RUN_COMMAND, ControlActor.Channel.OPERATOR))
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.Denied.class))
        .verifyComplete();
    verify(first).review(RUN_COMMAND);
    verify(second, never()).review(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void drainAndRecoverAreServerCommandsAnOwnerControls() {
    own("srv", new SupervisorScope.Server(), answering(Decision.ALLOW));
    final ControlReviewer reviewer = reviewer(true);

    final List<ControlRequest> requests =
        List.of(
            new ControlRequest(new ExecutionControlCommand.DrainCommand(true), null, null, null),
            new ControlRequest(
                new ExecutionControlCommand.RecoverCommand("old"), null, null, null));

    for (final ControlRequest request : requests) {
      StepVerifier.create(verdict(reviewer, request, ControlActor.Channel.OPERATOR))
          .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.Controlled.class))
          .verifyComplete();
    }
  }
}
