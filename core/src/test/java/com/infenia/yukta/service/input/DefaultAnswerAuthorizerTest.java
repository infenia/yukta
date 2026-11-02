// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;

/** The authorisation matrix: who may answer which request, and the rule that fails otherwise. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class DefaultAnswerAuthorizerTest {

  private static final String OP = "op";

  private final ExecutionControlStore runs = mock(ExecutionControlStore.class);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private final DefaultAnswerAuthorizer authorizer = new DefaultAnswerAuthorizer(runs, meters);

  private static ActorInfo human(final ApiRole role, final String... sessions) {
    return ActorInfo.human(OP, role, Set.of(sessions));
  }

  private static ActorInfo supervisor(
      final boolean canAnswer, final InputRisk max, final SupervisorScope scope) {
    return ActorInfo.supervisor("sup", new SupervisorCapabilities(null, canAnswer, max), scope);
  }

  private static InputRequest request(final InputRisk risk) {
    return request(risk, Audience.CURRENT_TIER);
  }

  private static InputRequest request(final InputRisk risk, final Audience audience) {
    return InputTestSupport.request(
        "r1",
        InputTestSupport.spec("k", InputKind.APPROVAL, risk, audience, List.of(), null, Map.of()));
  }

  /** Each case: the actor, the risk, and the rule that fails (null when the actor may answer). */
  /* default */ static Stream<Arguments> matrix() {
    final SupervisorScope global = new SupervisorScope.Global();
    return Stream.of(
        Arguments.of("operator on LOW", human(ApiRole.OPERATOR), InputRisk.LOW, null),
        Arguments.of("operator on MEDIUM", human(ApiRole.OPERATOR), InputRisk.MEDIUM, null),
        Arguments.of("operator on HIGH", human(ApiRole.OPERATOR), InputRisk.HIGH, null),
        Arguments.of("admin on HIGH", human(ApiRole.ADMIN), InputRisk.HIGH, null),
        Arguments.of("viewer is no target", human(ApiRole.VIEWER), InputRisk.LOW, "audience"),
        Arguments.of(
            "key of the right session", human(ApiRole.OPERATOR, "session"), InputRisk.LOW, null),
        Arguments.of(
            "key of another session", human(ApiRole.OPERATOR, "other"), InputRisk.LOW, "scope"),
        Arguments.of(
            "supervisor may answer LOW",
            supervisor(true, InputRisk.MEDIUM, global),
            InputRisk.LOW,
            null),
        Arguments.of(
            "supervisor may answer MEDIUM up to MEDIUM",
            supervisor(true, InputRisk.MEDIUM, global),
            InputRisk.MEDIUM,
            null),
        Arguments.of(
            "supervisor never answers HIGH",
            supervisor(true, InputRisk.HIGH, global),
            InputRisk.HIGH,
            "risk"),
        Arguments.of(
            "supervisor above its maxRisk",
            supervisor(true, InputRisk.LOW, global),
            InputRisk.MEDIUM,
            "risk"),
        Arguments.of(
            "supervisor without canAnswer",
            supervisor(false, InputRisk.HIGH, global),
            InputRisk.LOW,
            "risk"),
        Arguments.of(
            "supervisor of another session",
            supervisor(true, InputRisk.MEDIUM, new SupervisorScope.Session("other")),
            InputRisk.LOW,
            "scope"),
        Arguments.of(
            "supervisor of the session",
            supervisor(true, InputRisk.MEDIUM, new SupervisorScope.Session("session")),
            InputRisk.LOW,
            null),
        Arguments.of("unauthenticated", ActorInfo.unauthenticated(), InputRisk.LOW, "audience"),
        Arguments.of(
            "external is no default target", ActorInfo.external("t"), InputRisk.LOW, "audience"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void answerIsAllowedOrNamesTheFailedRule(
      final String name, final ActorInfo actor, final InputRisk risk, final String failedRule) {
    final var result = authorizer.authorize(new AnswerContext(request(risk), actor, false));

    if (failedRule == null) {
      StepVerifier.create(result).verifyComplete();
    } else {
      StepVerifier.create(result)
          .expectErrorSatisfies(
              error ->
                  assertThat(error)
                      .isInstanceOf(InputForbiddenException.class)
                      .hasMessageContaining("Rule '" + failedRule + "'")
                      .extracting(e -> ((InputForbiddenException) e).rule())
                      .isEqualTo(failedRule))
          .verify();
      assertThat(meters.counter("yukta.input.denied", "rule", failedRule).count()).isEqualTo(1.0);
    }
  }

  @Test
  void namedAudienceMustContainTheActor() {
    final InputRequest request =
        request(InputRisk.LOW, new Audience(List.of("key:alice", "supervisor:sup")));

    StepVerifier.create(
            authorizer.authorize(
                new AnswerContext(
                    request, ActorInfo.human("alice", ApiRole.OPERATOR, Set.of()), false)))
        .verifyComplete();
    StepVerifier.create(
            authorizer.authorize(
                new AnswerContext(
                    request, ActorInfo.human("bob", ApiRole.OPERATOR, Set.of()), false)))
        .expectErrorMessage("Rule 'audience': human 'bob' is not in the audience of the request")
        .verify();
    StepVerifier.create(
            authorizer.authorize(
                new AnswerContext(
                    request,
                    supervisor(true, InputRisk.MEDIUM, new SupervisorScope.Global()),
                    false)))
        .verifyComplete();
  }

  @Test
  void anAudienceSelectorCanNameOneSupervisorOrAnAdmin() {
    final InputRequest forOther = request(InputRisk.LOW, new Audience(List.of("supervisor:other")));
    final InputRequest forAdmin = request(InputRisk.LOW, new Audience(List.of("role:ADMIN")));

    StepVerifier.create(
            authorizer.authorize(
                new AnswerContext(
                    forOther,
                    supervisor(true, InputRisk.MEDIUM, new SupervisorScope.Global()),
                    false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("audience"))
        .verify();
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(forAdmin, human(ApiRole.OPERATOR), false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("audience"))
        .verify();
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(forAdmin, human(ApiRole.ADMIN), false)))
        .verifyComplete();
  }

  @Test
  void anExternalCallerMatchesItsOwnTierSelector() {
    final InputRequest request = request(InputRisk.LOW, new Audience(List.of("external:ops")));
    final InputRequest tiered =
        new InputRequest(
            request.id(),
            request.key(),
            request.sessionId(),
            request.workflowId(),
            request.executionId(),
            request.nodeId(),
            request.spec(),
            request.state(),
            request.version(),
            "ops",
            List.of("external:ops"),
            null,
            request.deadline(),
            request.createdAt(),
            request.updatedAt());

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(tiered, ActorInfo.external("ops"), false)))
        .verifyComplete();
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(tiered, ActorInfo.external("other"), false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("audience"))
        .verify();
  }

  @Test
  void requestsOwnTierTargetsDecideTheTierRule() {
    // an audience that admits operators, on a tier whose targets are admins only
    final InputRequest open = request(InputRisk.LOW, new Audience(List.of("role:OPERATOR")));
    final InputRequest adminsOnly =
        new InputRequest(
            open.id(),
            open.key(),
            open.sessionId(),
            open.workflowId(),
            open.executionId(),
            open.nodeId(),
            open.spec(),
            open.state(),
            open.version(),
            "second",
            List.of("role:ADMIN"),
            null,
            open.deadline(),
            open.createdAt(),
            open.updatedAt());

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(adminsOnly, human(ApiRole.OPERATOR), false)))
        .expectErrorSatisfies(
            error -> {
              assertThat(((InputForbiddenException) error).rule()).isEqualTo("tier");
              assertThat(error).hasMessageContaining("tier 'second'");
            })
        .verify();
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(adminsOnly, human(ApiRole.ADMIN), false)))
        .verifyComplete();
  }

  @Test
  void escalatingNeedsNoRightToAnswerButStillTheAudienceTierAndScope() {
    final ActorInfo cannotAnswer = supervisor(false, InputRisk.LOW, new SupervisorScope.Global());
    final ActorInfo wrongScope =
        supervisor(true, InputRisk.HIGH, new SupervisorScope.Session("other"));

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request(InputRisk.HIGH), cannotAnswer, true)))
        .verifyComplete();
    StepVerifier.create(
            authorizer.authorize(
                new AnswerContext(request(InputRisk.HIGH), human(ApiRole.OPERATOR), true)))
        .verifyComplete();
    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request(InputRisk.LOW), wrongScope, true)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("scope"))
        .verify();
    StepVerifier.create(
            authorizer.authorize(
                new AnswerContext(request(InputRisk.LOW), ActorInfo.unauthenticated(), true)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("audience"))
        .verify();
  }

  @Test
  void supervisorCannotAnswerForRunItStarted() {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.payload()).thenReturn(Map.of(DefaultAnswerAuthorizer.STARTER_KEY, "sup"));
    when(runs.findByExecutionId("exec")).thenReturn(Optional.of(control));
    final ActorInfo starter = supervisor(true, InputRisk.MEDIUM, new SupervisorScope.Global());

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request(InputRisk.LOW), starter, false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("separation"))
        .verify();
  }

  @Test
  void runStartedByAnotherSupervisorOrNoneDoesNotBlockTheAnswer() {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.payload()).thenReturn(Map.of(DefaultAnswerAuthorizer.STARTER_KEY, "someone-else"));
    when(runs.findByExecutionId("exec")).thenReturn(Optional.of(control));
    final ActorInfo answerer = supervisor(true, InputRisk.MEDIUM, new SupervisorScope.Global());

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request(InputRisk.LOW), answerer, false)))
        .verifyComplete();
  }

  @Test
  void supervisorWithoutCapabilitiesCannotAnswer() {
    final ActorInfo bare =
        new ActorInfo(
            ActorInfo.Type.SUPERVISOR, "sup", null, null, null, new SupervisorScope.Global());

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request(InputRisk.LOW), bare, false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("risk"))
        .verify();
  }

  @Test
  void supervisorWithoutScopeWatchesNothing() {
    final ActorInfo unscoped =
        new ActorInfo(
            ActorInfo.Type.SUPERVISOR,
            "sup",
            null,
            null,
            new SupervisorCapabilities(null, true, InputRisk.MEDIUM),
            null);

    StepVerifier.create(
            authorizer.authorize(new AnswerContext(request(InputRisk.LOW), unscoped, false)))
        .expectErrorSatisfies(
            error -> assertThat(((InputForbiddenException) error).rule()).isEqualTo("scope"))
        .verify();
  }
}
