// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.Delegation;
import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.DelegationMode;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.supervisor.composite.SupervisorResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** An owner's need for approval goes to its parents first, and only then to a person. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ControlReviewerParentsTest {

  private static final String PAUSE = "PauseWorkflowCommand";

  private static final ControlRequest REQUEST =
      new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "s", "w");

  private final AttachedSupervisors attached = new AttachedSupervisors();

  private final SupervisorResolver resolver =
      new SupervisorResolver(
          new StaticListableBeanFactory().getBeanProvider(SupervisorFactory.class));

  private final ControlReviewer reviewer = new ControlReviewer(providerOf(), Duration.ofSeconds(2));

  {
    reviewer.setAttachedSupervisors(attached);
    reviewer.setHierarchy(resolver);
  }

  private ExecutionSupervisor needing(final InputRisk risk) {
    final ExecutionSupervisor owner = mock(ExecutionSupervisor.class);
    when(owner.review(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Mono.just(
                Decision.requireApproval("needs a look", new ApprovalNeed(risk, null, null))));
    return owner;
  }

  private ExecutionSupervisor answering(final Decision decision) {
    final ExecutionSupervisor parent = mock(ExecutionSupervisor.class);
    when(parent.review(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(decision));
    return parent;
  }

  private void owner(final ExecutionSupervisor delegate, final Supervisor shell) {
    attached.put(
        new BoundSupervisor(
            new SupervisorAttachment(
                "own", shell.id(), new SupervisorScope.Workflow("w", "s"), true),
            delegate,
            false,
            new SupervisorCapabilities(null, false, InputRisk.LOW, Set.of(PAUSE)),
            false));
  }

  private void parent(final String id, final ExecutionSupervisor delegate) {
    attached.put(
        new BoundSupervisor(
            new SupervisorAttachment("att-" + id, id, new SupervisorScope.Global(), true),
            delegate,
            false,
            new SupervisorCapabilities(null, false, InputRisk.LOW),
            false));
  }

  private void known(final Supervisor... all) {
    resolver.refresh(List.of(all));
  }

  private static Supervisor shell(
      final String id, final Map<DelegationKind, Delegation> delegation, final String... parents) {
    return new Supervisor(id, "t", Map.of(), false, null, List.of(parents), delegation);
  }

  private Mono<ReviewVerdict> verdict() {
    return reviewer
        .verdict(REQUEST, Set.of())
        .contextWrite(Context.of(ControlActor.CHANNEL_KEY, ControlActor.Channel.OPERATOR.name()));
  }

  @Test
  void parentThatAllowsLetsTheCommandThroughWithoutPerson() {
    final Supervisor child = shell("child", Map.of(), "boss");
    known(child, shell("boss", Map.of()));
    owner(needing(InputRisk.LOW), child);
    parent("boss", answering(Decision.ALLOW));

    StepVerifier.create(verdict()).expectNext(ReviewVerdict.ALLOWED).verifyComplete();
  }

  @Test
  void parentThatRefusesEndsItAndNamesItself() {
    final Supervisor child = shell("child", Map.of(), "boss");
    known(child, shell("boss", Map.of()));
    owner(needing(InputRisk.LOW), child);
    parent("boss", answering(Decision.deny("not now")));

    StepVerifier.create(verdict())
        .assertNext(
            v ->
                assertThat(v)
                    .isInstanceOfSatisfying(
                        ReviewVerdict.Denied.class,
                        d -> assertThat(d.reason()).isEqualTo("boss: not now")))
        .verifyComplete();
  }

  @Test
  void parentThatIsNotRunningIsSilentSoPersonDecidesAndChainIsRecorded() {
    final Supervisor child = shell("child", Map.of(), "boss");
    known(child, shell("boss", Map.of()));
    owner(needing(InputRisk.LOW), child);

    StepVerifier.create(verdict())
        .assertNext(
            v ->
                assertThat(v)
                    .isInstanceOfSatisfying(
                        ReviewVerdict.NeedsApproval.class,
                        n -> assertThat(n.reason()).contains("asked [boss:silent]")))
        .verifyComplete();
  }

  @Test
  void highRiskNeedGoesStraightToPersonAndNoParentIsAsked() {
    final Supervisor child = shell("child", Map.of(), "boss");
    known(child, shell("boss", Map.of()));
    owner(needing(InputRisk.HIGH), child);
    final ExecutionSupervisor boss = answering(Decision.ALLOW);
    parent("boss", boss);

    StepVerifier.create(verdict())
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.NeedsApproval.class))
        .verifyComplete();
    verify(boss, never()).review(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void ownerWithoutParentsKeepsItsNeedForPerson() {
    final Supervisor child = shell("child", Map.of());
    known(child);
    owner(needing(InputRisk.LOW), child);

    StepVerifier.create(verdict())
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.NeedsApproval.class))
        .verifyComplete();
  }

  @Test
  void allModeNeedsEveryParentAndOneRefusalIsFinal() {
    final Supervisor child =
        shell(
            "child",
            Map.of(
                DelegationKind.APPROVAL, new Delegation(DelegationMode.ALL, Duration.ofSeconds(1))),
            "a",
            "b");
    known(child, shell("a", Map.of()), shell("b", Map.of()));
    owner(needing(InputRisk.LOW), child);
    parent("a", answering(Decision.ALLOW));
    parent("b", answering(Decision.deny("closed")));

    StepVerifier.create(verdict())
        .assertNext(
            v ->
                assertThat(v)
                    .isInstanceOfSatisfying(
                        ReviewVerdict.Denied.class,
                        d -> assertThat(d.reason()).contains("b: closed")))
        .verifyComplete();
  }

  @Test
  void siblingThatIsNotParentIsNeverAsked() {
    final Supervisor child = shell("child", Map.of(), "boss");
    known(child, shell("boss", Map.of()), shell("sibling", Map.of()));
    owner(needing(InputRisk.LOW), child);
    final ExecutionSupervisor sibling = answering(Decision.ALLOW);
    parent("sibling", sibling);

    StepVerifier.create(verdict())
        .assertNext(v -> assertThat(v).isInstanceOf(ReviewVerdict.NeedsApproval.class))
        .verifyComplete();
    verify(sibling, never()).review(org.mockito.ArgumentMatchers.any());
  }
}
