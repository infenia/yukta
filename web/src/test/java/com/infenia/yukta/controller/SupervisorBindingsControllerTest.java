// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.controller.SupervisorBindingsController.BindingBody;
import com.infenia.yukta.controller.SupervisorBindingsController.ProfileBody;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.supervisor.SupervisorDecision;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link SupervisorBindingsController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class SupervisorBindingsControllerTest {

  private final SupervisorService service = mock(SupervisorService.class);

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorBindingsController controller =
      new SupervisorBindingsController(service, host);

  @Test
  void nullSettingsAreAcceptedAndNotShown() {
    when(service.profiles()).thenReturn(Flux.empty());
    when(service.saveProfile(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(
            controller.replaceProfile(
                "p",
                new ProfileBody("p", "t", java.util.Collections.singletonMap("k", null), false)))
        .assertNext(r -> assertThat(r.data().settings()).isEmpty())
        .verifyComplete();
  }

  @Test
  void blankBindingIdIsReplacedByGeneratedOne() {
    when(service.attach(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(controller.attach(new BindingBody("  ", "p", "global", true)))
        .assertNext(r -> assertThat(r.data().id()).isNotBlank().isNotEqualTo("  "))
        .verifyComplete();
  }

  @Test
  void typesAreListed() {
    when(service.types()).thenReturn(List.of("rule-supervisor"));

    StepVerifier.create(controller.types())
        .assertNext(r -> assertThat(r.data()).containsExactly("rule-supervisor"))
        .verifyComplete();
  }

  @Test
  void secretsAreHiddenAtEveryDepth() {
    final Map<String, Object> settings =
        Map.of(
            "apiKey", "sk-123",
            "model", "m",
            "nested", Map.of("password", "pw", "url", "http://x"));
    when(service.profiles())
        .thenReturn(Flux.just(new SupervisorProfile("p", "model-control-plane", settings, true)));

    StepVerifier.create(controller.profiles())
        .assertNext(
            r -> {
              final Map<String, Object> shown = r.data().getFirst().settings();
              assertThat(shown).containsEntry("apiKey", "***").containsEntry("model", "m");
              assertThat(shown.get("nested"))
                  .isEqualTo(Map.of("password", "***", "url", "http://x"));
              assertThat(r.data().getFirst().dryRun()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void profileIsCreatedUnlessItsIdExists() {
    final ProfileBody body = new ProfileBody("p", "t", Map.of("k", "v"), false);
    when(service.profiles()).thenReturn(Flux.empty());
    when(service.saveProfile(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(controller.createProfile(body))
        .assertNext(r -> assertThat(r.status()).isEqualTo(201))
        .verifyComplete();

    when(service.profiles())
        .thenReturn(Flux.just(new SupervisorProfile("p", "t", Map.of(), false)));
    StepVerifier.create(controller.createProfile(body))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void replacingProfileUsesThePathId() {
    when(service.profiles()).thenReturn(Flux.empty());
    when(service.saveProfile(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(controller.replaceProfile("p", new ProfileBody("ignored", "t", null, true)))
        .assertNext(r -> assertThat(r.data().id()).isEqualTo("p"))
        .verifyComplete();
  }

  @Test
  void replacingProfileKeepsTheStoredSecretBehindAMask() {
    when(service.profiles())
        .thenReturn(
            Flux.just(
                new SupervisorProfile("p", "t", Map.of("apiKey", "sk-1", "model", "a"), false)));
    when(service.saveProfile(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(
            controller.replaceProfile(
                "p", new ProfileBody("p", "t", Map.of("apiKey", "***", "model", "b"), false)))
        .expectNextCount(1)
        .verifyComplete();
    final ArgumentCaptor<SupervisorProfile> saved =
        ArgumentCaptor.forClass(SupervisorProfile.class);
    verify(service).saveProfile(saved.capture());
    assertThat(saved.getValue().settings())
        .containsEntry("apiKey", "sk-1")
        .containsEntry("model", "b");
  }

  @Test
  void secretsInsideListsAreHidden() {
    when(service.profiles())
        .thenReturn(
            Flux.just(
                new SupervisorProfile(
                    "p",
                    "t",
                    Map.of("targets", List.of(Map.of("token", "abc", "url", "u"))),
                    false)));

    StepVerifier.create(controller.profiles())
        .assertNext(
            r ->
                assertThat(r.data().getFirst().settings().get("targets"))
                    .isEqualTo(List.of(Map.of("token", "***", "url", "u"))))
        .verifyComplete();
  }

  @Test
  void missingRequiredFieldsAreClientErrors() {
    StepVerifier.create(controller.createProfile(new ProfileBody(null, "t", Map.of(), false)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(controller.attach(new BindingBody("b", " ", "global", true)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void deletingProfileAndBindingDelegates() {
    when(service.deleteProfile("p")).thenReturn(Mono.empty());
    when(service.detach("b")).thenReturn(Mono.empty());

    StepVerifier.create(controller.deleteProfile("p"))
        .assertNext(r -> assertThat(r.status()).isEqualTo(200))
        .verifyComplete();
    StepVerifier.create(controller.detach("b"))
        .assertNext(r -> assertThat(r.status()).isEqualTo(200))
        .verifyComplete();
    verify(service).deleteProfile("p");
    verify(service).detach("b");
  }

  @Test
  void attachingParsesTheScopeAndGeneratesIdAndDefaultsToEnabled() {
    when(service.attach(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(controller.attach(new BindingBody(null, "p", "workflow:A/w", null)))
        .assertNext(
            r -> {
              assertThat(r.status()).isEqualTo(201);
              assertThat(r.data().id()).isNotBlank();
              assertThat(r.data().scope()).isEqualTo("workflow:A/w");
              assertThat(r.data().enabled()).isTrue();
            })
        .verifyComplete();

    final ArgumentCaptor<SupervisorBinding> sent = ArgumentCaptor.forClass(SupervisorBinding.class);
    StepVerifier.create(controller.attach(new BindingBody("b1", "p", "session:A", false)))
        .expectNextCount(1)
        .verifyComplete();
    verify(service, org.mockito.Mockito.times(2)).attach(sent.capture());
    assertThat(sent.getValue())
        .isEqualTo(new SupervisorBinding("b1", "p", new SupervisorScope.Session("A"), false));
  }

  @Test
  void badScopeIsRejectedWithoutAttaching() {
    StepVerifier.create(controller.attach(new BindingBody("b", "p", "everything", true)))
        .expectError(IllegalArgumentException.class)
        .verify();
    verify(service, never()).attach(any());
  }

  @Test
  void bindingsAreListedWithTheirScopeText() {
    when(service.bindings())
        .thenReturn(Flux.just(new SupervisorBinding("b", "p", new SupervisorScope.Global(), true)));

    StepVerifier.create(controller.bindings())
        .assertNext(
            r -> assertThat(r.data()).containsExactly(new BindingBody("b", "p", "global", true)))
        .verifyComplete();
  }

  @Test
  void decisionsAreFilteredAndTheLimitIsKeptInRange() {
    final SupervisorDecision decision =
        new SupervisorDecision(java.time.Instant.now(), "b1", "applied", "Pause", "e", null);
    when(host.decisions("b1", 50)).thenReturn(List.of(decision));
    when(host.decisions(null, 500)).thenReturn(List.of());
    when(host.decisions(null, 1)).thenReturn(List.of());

    StepVerifier.create(controller.decisions("b1", 50))
        .assertNext(r -> assertThat(r.data()).containsExactly(decision))
        .verifyComplete();
    StepVerifier.create(controller.decisions(null, 99_999)).expectNextCount(1).verifyComplete();
    StepVerifier.create(controller.decisions(null, -3)).expectNextCount(1).verifyComplete();
    verify(host).decisions(null, 500);
    verify(host).decisions(null, 1);
  }

  @Test
  void hiddenSecretKeepsItsStoredValueWhenProfileIsSavedAgain() {
    when(service.profiles())
        .thenReturn(
            Flux.just(
                new SupervisorProfile(
                    "p",
                    "model-control-plane",
                    Map.of(
                        "apiKey", "sk-real", "model", "old", "nested", Map.of("token", "tk-real")),
                    false)));
    when(service.saveProfile(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(
            controller.replaceProfile(
                "p",
                new ProfileBody(
                    "p",
                    "model-control-plane",
                    Map.of(
                        "apiKey", "***",
                        "model", "new",
                        "other", "***",
                        "fresh", Map.of("a", 1),
                        "nested", Map.of("token", "***", "plain", "x")),
                    true)))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<SupervisorProfile> saved =
        ArgumentCaptor.forClass(SupervisorProfile.class);
    verify(service).saveProfile(saved.capture());
    assertThat(saved.getValue().settings())
        .containsEntry("apiKey", "sk-real")
        .containsEntry("model", "new")
        .containsEntry("other", "***")
        .containsEntry("fresh", Map.of("a", 1))
        .containsEntry("nested", Map.of("token", "tk-real", "plain", "x"));
    assertThat(saved.getValue().dryRun()).isTrue();
  }
}
