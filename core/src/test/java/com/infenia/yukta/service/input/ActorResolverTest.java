// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.supervisor.AttachedSupervisors;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Who sends a command is read from the kind the sender authenticated as, never from its name. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class ActorResolverTest {

  private final AttachedSupervisors attached = mock(AttachedSupervisors.class);

  private final ApiAuthenticator authenticator = mock(ApiAuthenticator.class);

  private final ActorResolver resolver = new ActorResolver(attached, authenticator);

  private void attach(final String id, final boolean canAnswer) {
    when(attached.find(id))
        .thenReturn(
            Optional.of(
                new BoundSupervisor(
                    new SupervisorBinding(id, "p", new SupervisorScope.Session("s1"), true),
                    mock(ExecutionSupervisor.class),
                    false,
                    new SupervisorCapabilities(null, canAnswer, InputRisk.MEDIUM))));
  }

  private static ContextView key(final String name, final ApiRole role, final String... sessions) {
    return Context.of(
        ControlActor.CONTEXT_KEY,
        "key:" + name,
        ControlActor.KIND_KEY,
        ControlActor.Kind.KEY,
        CallerIdentity.CONTEXT_KEY,
        new ApiKeys.Match(name, role, Set.of(sessions)));
  }

  @Test
  void keyIsHumanWithItsRoleAndSessions() {
    final ActorInfo actor = resolver.resolve(key("alice", ApiRole.OPERATOR, "s1"));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.HUMAN);
    assertThat(actor.id()).isEqualTo("alice");
    assertThat(actor.role()).isEqualTo(ApiRole.OPERATOR);
    assertThat(actor.sessions()).containsExactly("s1");
  }

  @Test
  void supervisorIsFoundByIdWithItsCapabilitiesAndScope() {
    attach("guard", true);

    final ActorInfo actor =
        resolver.resolve(
            Context.of(
                ControlActor.CONTEXT_KEY,
                "guard",
                ControlActor.KIND_KEY,
                ControlActor.Kind.SUPERVISOR));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.SUPERVISOR);
    assertThat(actor.capabilities().canAnswer()).isTrue();
    assertThat(actor.scope()).isEqualTo(new SupervisorScope.Session("s1"));
  }

  @Test
  void supervisorThatIsNotAttachedCanNeverAnswer() {
    final ActorInfo actor =
        resolver.resolve(
            Context.of(
                ControlActor.CONTEXT_KEY,
                "bean",
                ControlActor.KIND_KEY,
                ControlActor.Kind.SUPERVISOR));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.SUPERVISOR);
    assertThat(actor.capabilities().canAnswer()).isFalse();
    assertThat(actor.scope()).isEqualTo(new SupervisorScope.Global());
  }

  @Test
  void keyNamedLikeSupervisorGainsNoSupervisorRights() {
    attach("admin", true);

    // the key is called admin, and a supervisor with the id admin may answer
    final ActorInfo actor = resolver.resolve(key("admin", ApiRole.VIEWER));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.HUMAN);
    assertThat(actor.capabilities()).isNull();
    assertThat(actor.role()).isEqualTo(ApiRole.VIEWER);
  }

  @Test
  void anActorThatClaimsToBeSupervisorWithoutTheKindIsNotTrusted() {
    attach("guard", true);

    final ActorInfo actor = resolver.resolve(Context.of(ControlActor.CONTEXT_KEY, "guard"));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.UNAUTHENTICATED);
  }

  @Test
  void keyKindWithoutResolvedKeyHasNoIdentity() {
    final ActorInfo actor =
        resolver.resolve(
            Context.of(
                ControlActor.CONTEXT_KEY, "key:x", ControlActor.KIND_KEY, ControlActor.Kind.KEY));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.UNAUTHENTICATED);
  }

  @Test
  void tokenIsAnExternalCallerNamedByItsTier() {
    final ActorInfo prefixed =
        resolver.resolve(
            Context.of(
                ControlActor.CONTEXT_KEY,
                "token:ops",
                ControlActor.KIND_KEY,
                ControlActor.Kind.TOKEN));
    final ActorInfo bare =
        resolver.resolve(
            Context.of(
                ControlActor.CONTEXT_KEY, "ops", ControlActor.KIND_KEY, ControlActor.Kind.TOKEN));

    assertThat(prefixed.type()).isEqualTo(ActorInfo.Type.EXTERNAL);
    assertThat(prefixed.id()).isEqualTo("ops");
    assertThat(bare.id()).isEqualTo("ops");
  }

  @Test
  void theSingleApiKeyHasMatchButNoActor() {
    final ActorInfo actor =
        resolver.resolve(
            Context.of(
                CallerIdentity.CONTEXT_KEY, new ApiKeys.Match("default", ApiRole.ADMIN, Set.of())));

    assertThat(actor.type()).isEqualTo(ActorInfo.Type.HUMAN);
    assertThat(actor.id()).isEqualTo("default");
  }

  @Test
  void withoutAnyIdentityPersonOnlyWhenAuthenticationIsOff() {
    when(authenticator.isOpen(ApiAuthenticator.Scope.REST)).thenReturn(true);
    when(authenticator.isOpen(ApiAuthenticator.Scope.MCP)).thenReturn(true);

    final ActorInfo open = resolver.resolve(Context.empty());

    assertThat(open.type()).isEqualTo(ActorInfo.Type.HUMAN);
    assertThat(open.role()).isEqualTo(ApiRole.ADMIN);
  }

  @Test
  void withoutAnyIdentityWhereKeyIsRequiredThereAreNoRights() {
    when(authenticator.isOpen(ApiAuthenticator.Scope.REST)).thenReturn(false);

    assertThat(resolver.resolve(Context.empty()).type()).isEqualTo(ActorInfo.Type.UNAUTHENTICATED);

    when(authenticator.isOpen(ApiAuthenticator.Scope.REST)).thenReturn(true);
    when(authenticator.isOpen(ApiAuthenticator.Scope.MCP)).thenReturn(false);

    assertThat(resolver.resolve(Context.empty()).type()).isEqualTo(ActorInfo.Type.UNAUTHENTICATED);
  }

  @Test
  void resolveReadsTheContextOfTheSubscription() {
    StepVerifier.create(resolver.resolve().contextWrite(key("alice", ApiRole.ADMIN)))
        .assertNext(actor -> assertThat(actor.id()).isEqualTo("alice"))
        .verifyComplete();
  }

  @Test
  void supervisorOrTokenKindWithoutActorHasNoIdentity() {
    assertThat(resolver.resolve(Context.of(ControlActor.KIND_KEY, ControlActor.Kind.SUPERVISOR)).type())
        .isEqualTo(ActorInfo.Type.UNAUTHENTICATED);
    assertThat(resolver.resolve(Context.of(ControlActor.KIND_KEY, ControlActor.Kind.TOKEN)).type())
        .isEqualTo(ActorInfo.Type.UNAUTHENTICATED);
  }
}
