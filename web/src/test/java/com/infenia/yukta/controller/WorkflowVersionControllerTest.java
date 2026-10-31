// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.workflow.store.DefinitionRevision;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class WorkflowVersionControllerTest {

  private static final String VERSION_B = "0002-b";

  private static final String BASE = "/api/sessions/s1/workflows/w1";

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToController(new WorkflowVersionController(definitions, controlBus))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void versionsAreListed() {
    when(definitions.revisions("s1", "w1"))
        .thenReturn(Flux.just(new DefinitionRevision(VERSION_B, Instant.EPOCH)));

    client
        .get()
        .uri(BASE + "/versions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].id")
        .isEqualTo(VERSION_B);
  }

  @Test
  void diffComparesVersionWithCurrentDefinition_orWithAnotherVersion() {
    final WorkflowDefinition older =
        new WorkflowDefinition(
            "w1", "d", List.of(new WorkflowDefinition.Node("a", "T", Map.of())), List.of());
    final WorkflowDefinition current =
        new WorkflowDefinition(
            "w1",
            "d",
            List.of(
                new WorkflowDefinition.Node("a", "T", Map.of()),
                new WorkflowDefinition.Node("b", "T", Map.of())),
            List.of());
    when(definitions.findRevision("s1", "w1", "0001-a")).thenReturn(Mono.just(older));
    when(definitions.findRevision("s1", "w1", VERSION_B)).thenReturn(Mono.just(current));
    when(definitions.find("s1", "w1")).thenReturn(Mono.just(current));

    for (final String uri :
        List.of(
            BASE + "/versions/diff?from=0001-a",
            BASE + "/versions/diff?from=0001-a&to=",
            BASE + "/versions/diff?from=0001-a&to=" + VERSION_B)) {
      client
          .get()
          .uri(uri)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.addedNodes[0]")
          .isEqualTo("b");
    }
  }

  @Test
  void diffOfAnUnknownVersionIsNotFound() {
    when(definitions.findRevision("s1", "w1", "nope")).thenReturn(Mono.empty());
    when(definitions.find("s1", "w1")).thenReturn(Mono.empty());

    client.get().uri(BASE + "/versions/diff?from=nope").exchange().expectStatus().isNotFound();
  }

  @Test
  void rollbackSavesTheOldVersionAgain_andPreparesIt() {
    final WorkflowDefinition old =
        new WorkflowDefinition(
            "w1",
            "old",
            List.of(new WorkflowDefinition.Node("n", "CONSTANT_SOURCE", Map.of())),
            List.of());
    when(definitions.findRevision("s1", "w1", "0001-a")).thenReturn(Mono.just(old));
    when(definitions.find("s1", "w1")).thenReturn(Mono.empty());
    when(definitions.save("s1", old)).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow("s1", old)).thenReturn(Mono.empty());

    client
        .post()
        .uri(BASE + "/rollback?version=0001-a")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.workflowId")
        .isEqualTo("w1");
    verify(definitions).save("s1", old);
    verify(controlBus).compileAndCacheWorkflow("s1", old);
  }

  @Test
  void rollbackThatDoesNotCompileLeavesTheCurrentDefinitionInPlace() {
    final WorkflowDefinition old =
        new WorkflowDefinition(
            "w1",
            "old",
            List.of(new WorkflowDefinition.Node("n", "REMOVED_TYPE", Map.of())),
            List.of());
    final WorkflowDefinition current =
        new WorkflowDefinition(
            "w1",
            "current",
            List.of(new WorkflowDefinition.Node("n", "CONSTANT_SOURCE", Map.of())),
            List.of());
    when(definitions.findRevision("s1", "w1", "0001-a")).thenReturn(Mono.just(old));
    when(definitions.find("s1", "w1")).thenReturn(Mono.just(current));
    when(definitions.save("s1", old)).thenReturn(Mono.empty());
    when(definitions.save("s1", current)).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow("s1", old))
        .thenReturn(Mono.error(new IllegalArgumentException("unknown plugin type")));

    client
        .post()
        .uri(BASE + "/rollback?version=0001-a")
        .exchange()
        .expectStatus()
        .is4xxClientError();
    verify(definitions).save("s1", current);
  }

  @Test
  void rollbackToAnUnknownVersionIsNotFound() {
    when(definitions.findRevision("s1", "w1", "nope")).thenReturn(Mono.empty());

    client.post().uri(BASE + "/rollback?version=nope").exchange().expectStatus().isNotFound();
  }
}
