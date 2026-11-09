// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The rules document of a rules supervisor, read, replaced and replayed without its common
 * settings.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorRulesTest {

  private static final Supervisor RULES =
      new Supervisor(
          "r",
          "rules",
          Map.of(
              "rules", List.of("first"),
              "token", "abc",
              "allowedCommands", List.of("PauseWorkflowCommand")),
          false);

  private final SupervisorService service = Mockito.mock(SupervisorService.class);

  @BeforeEach
  void setUp() {
    when(service.supervisors())
        .thenReturn(Flux.just(RULES, new Supervisor("plain", "callout", Map.of(), false)));
    when(service.versions(ArgumentMatchers.anyString()))
        .thenReturn(Mono.just(List.of(new SupervisorVersions.Version(3, Instant.EPOCH, RULES))));
    when(service.saveSupervisor(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
  }

  @Test
  void readLeavesTheCommonSettingsOut() {
    StepVerifier.create(SupervisorRules.read(service, "r"))
        .assertNext(
            doc -> {
              assertThat(doc.version()).isEqualTo(3);
              assertThat(doc.rules()).containsOnlyKeys("rules", "token");
            })
        .verifyComplete();
  }

  @Test
  void supervisorWithoutKeptVersionIsVersionZero() {
    when(service.versions("r")).thenReturn(Mono.just(List.of()));
    StepVerifier.create(SupervisorRules.read(service, "r"))
        .assertNext(doc -> assertThat(doc.version()).isZero())
        .verifyComplete();
  }

  @Test
  void unknownIsNotFoundAndAnotherEngineIsConflict() {
    StepVerifier.create(SupervisorRules.read(service, "gone"))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(SupervisorRules.read(service, "plain"))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void saveKeepsTheCommonSettingsAndTheHiddenSecret() {
    StepVerifier.create(
            SupervisorRules.save(service, "r", Map.of("rules", List.of("second"), "token", "***")))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<Supervisor> saved = ArgumentCaptor.forClass(Supervisor.class);
    verify(service).saveSupervisor(saved.capture());
    assertThat(saved.getValue().settings())
        .containsOnly(
            Map.entry("rules", List.of("second")),
            Map.entry("token", "abc"),
            Map.entry("allowedCommands", List.of("PauseWorkflowCommand")));
  }

  @Test
  void commonSettingInTheRulesIsRefused() {
    StepVerifier.create(SupervisorRules.save(service, "r", Map.of("initiative", List.of())))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("initiative"))
        .verify();
    StepVerifier.create(
            SupervisorRules.replay(service, "r", "e1", Map.of("allowedCommands", List.of())))
        .expectError(IllegalArgumentException.class)
        .verify();
    verify(service, Mockito.never()).saveSupervisor(any());
  }

  @Test
  void replayTriesTheDraftWithTheStoredCommonSettings() {
    when(service.replay(ArgumentMatchers.eq("r"), any(), ArgumentMatchers.eq("e1")))
        .thenReturn(Mono.just(List.of()));

    StepVerifier.create(SupervisorRules.replay(service, "r", "e1", Map.of("rules", List.of("d"))))
        .expectNext(List.of())
        .verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> tried = ArgumentCaptor.forClass(Map.class);
    verify(service).replay(ArgumentMatchers.eq("r"), tried.capture(), ArgumentMatchers.eq("e1"));
    assertThat(tried.getValue())
        .containsOnly(
            Map.entry("rules", List.of("d")),
            Map.entry("allowedCommands", List.of("PauseWorkflowCommand")));
  }

  @Test
  void replayWithoutDraftUsesTheStoredRules() {
    when(service.replay("r", null, "e1")).thenReturn(Mono.just(List.of()));

    StepVerifier.create(SupervisorRules.replay(service, "r", "e1", null))
        .expectNext(List.of())
        .verifyComplete();
  }
}
