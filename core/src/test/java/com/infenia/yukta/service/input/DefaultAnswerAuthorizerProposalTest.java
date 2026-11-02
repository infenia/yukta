// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** The rules for answering the approval request of a parked control command. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert"
})
class DefaultAnswerAuthorizerProposalTest {

  private static final SupervisorCapabilities CAN_ANSWER =
      new SupervisorCapabilities(null, true, InputRisk.MEDIUM);

  private static final SupervisorScope WORKFLOW =
      SupervisorScope.parse("workflow:session/workflow");

  private static final SupervisorScope SESSION = new SupervisorScope.Session("session");

  private static final ActorInfo SECTION_SUPERVISOR =
      ActorInfo.supervisor(
          "section", CAN_ANSWER, SupervisorScope.parse("workflow:session/workflow//node"));

  private static final ActorInfo WORKFLOW_SUPERVISOR =
      ActorInfo.supervisor("workflow", CAN_ANSWER, WORKFLOW);

  private static final ActorInfo SESSION_SUPERVISOR =
      ActorInfo.supervisor("wide", CAN_ANSWER, SESSION);

  private static final ActorInfo ALICE = ActorInfo.human("alice", ApiRole.OPERATOR, Set.of());

  private CommandProposals.Proposal current;

  private final DefaultAnswerAuthorizer authorizer =
      new DefaultAnswerAuthorizer(
          mock(ExecutionControlStore.class),
          new SimpleMeterRegistry(),
          id -> "r1".equals(id) ? Optional.ofNullable(this.current) : Optional.empty());

  private static InputRequest request(final String id, final InputRisk risk) {
    return InputTestSupport.request(
        id,
        InputTestSupport.spec(
            "k", InputKind.APPROVAL, risk, Audience.CURRENT_TIER, List.of(), null, Map.of()));
  }

  private void expectRule(final ActorInfo actor, final InputRisk risk, final String rule) {
    StepVerifier.create(authorizer.authorize(new AnswerContext(request("r1", risk), actor, false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo(rule))
        .verify();
  }

  private void expectAllowed(final ActorInfo actor, final InputRisk risk) {
    StepVerifier.create(authorizer.authorize(new AnswerContext(request("r1", risk), actor, false)))
        .verifyComplete();
  }

  @Test
  void theSenderOfTheCommandCannotApproveIt() {
    current = new CommandProposals.Proposal("StopWorkflowCommand", ALICE, List.of());
    expectRule(ALICE, InputRisk.HIGH, "separation");
    expectAllowed(ActorInfo.human("bob", ApiRole.OPERATOR, Set.of()), InputRisk.HIGH);
    current = new CommandProposals.Proposal("StopWorkflowCommand", WORKFLOW_SUPERVISOR, List.of());
    expectAllowed(ALICE, InputRisk.HIGH);
  }

  @Test
  void senderWithoutIdentityIsNotSeparated() {
    current = new CommandProposals.Proposal("StopWorkflowCommand", null, List.of());
    expectAllowed(ALICE, InputRisk.HIGH);
  }

  @Test
  void supervisorThatAskedForTheApprovalCannotGiveIt() {
    current = new CommandProposals.Proposal("StopWorkflowCommand", ALICE, List.of("wide"));
    expectRule(SESSION_SUPERVISOR, InputRisk.MEDIUM, "separation");
    expectAllowed(WORKFLOW_SUPERVISOR, InputRisk.MEDIUM);
  }

  @Test
  void supervisorApprovesAnotherSupervisorsCommandOnlyWithStrictlyWiderScope() {
    current = new CommandProposals.Proposal("StopNodeCommand", SECTION_SUPERVISOR, List.of());
    expectAllowed(WORKFLOW_SUPERVISOR, InputRisk.MEDIUM);
    expectAllowed(SESSION_SUPERVISOR, InputRisk.MEDIUM);

    current = new CommandProposals.Proposal("StopNodeCommand", WORKFLOW_SUPERVISOR, List.of());
    expectRule(SECTION_SUPERVISOR, InputRisk.MEDIUM, "rank");
    expectRule(ActorInfo.supervisor("peer", CAN_ANSWER, WORKFLOW), InputRisk.MEDIUM, "rank");
    expectAllowed(SESSION_SUPERVISOR, InputRisk.MEDIUM);
  }

  @Test
  void supervisorMayApproveCommandOfSenderWithoutIdentity() {
    current = new CommandProposals.Proposal("StopNodeCommand", null, List.of());
    expectAllowed(SESSION_SUPERVISOR, InputRisk.MEDIUM);
  }

  @Test
  void supervisorMayApprovePersonsCommand() {
    current = new CommandProposals.Proposal("StopNodeCommand", ALICE, List.of());
    expectAllowed(SESSION_SUPERVISOR, InputRisk.MEDIUM);
  }

  @Test
  void proposerWithoutScopeCannotBeCovered() {
    current =
        new CommandProposals.Proposal(
            "StopNodeCommand",
            new ActorInfo(ActorInfo.Type.SUPERVISOR, "x", null, null, CAN_ANSWER, null),
            List.of());
    expectRule(SESSION_SUPERVISOR, InputRisk.MEDIUM, "rank");
  }

  @Test
  void supervisorNeverApprovesHighRisk() {
    current = new CommandProposals.Proposal("StopNodeCommand", SECTION_SUPERVISOR, List.of());
    expectRule(SESSION_SUPERVISOR, InputRisk.HIGH, "risk");
  }

  @Test
  void otherRequestsAndEscalationsSkipTheRules() {
    current = new CommandProposals.Proposal("StopWorkflowCommand", ALICE, List.of());
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request("r2", InputRisk.HIGH), ALICE, false)))
        .verifyComplete();
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request("r1", InputRisk.HIGH), ALICE, true)))
        .verifyComplete();
  }

  @Test
  void theTwoArgumentConstructorKnowsNoProposals() {
    StepVerifier.create(
            new DefaultAnswerAuthorizer(
                    mock(ExecutionControlStore.class), new SimpleMeterRegistry())
                .authorize(new AnswerContext(request("r1", InputRisk.HIGH), ALICE, false)))
        .verifyComplete();
    assertThat(CommandProposals.NONE.find("x")).isEmpty();
  }
}
