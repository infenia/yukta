// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.runtime.ExecutionScheduler;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.TooManyStaticImports")
class WorkflowStarterTest {

  private static final String CUSTOMER = "customer";

  private static final String DRAFT_ID = "__test__.x";

  @Test
  void startWithoutGuardIsRefusedWhenTheWorkflowIsUnknown() {
    final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
    when(definitions.find("s", "w")).thenReturn(Mono.empty());
    final WorkflowStarter starter =
        new WorkflowStarter(
            definitions,
            mock(PreparedWorkflowCache.class),
            mock(WorkflowOrchestrator.class),
            mock(ExecutionScheduler.class),
            new StartIdempotency(
                Duration.ofMinutes(1), Ticker.systemTicker(), mock(ExecutionLogStore.class), 10));

    StepVerifier.create(starter.start("s", "w", Map.of(), null))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void draftIsPreparedAndRunWithoutBeingCached() {
    final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
    final PreparedWorkflowCache cache = mock(PreparedWorkflowCache.class);
    final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
    final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
    final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
    final WorkflowDefinition draft =
        new WorkflowDefinition(DRAFT_ID, "d", java.util.List.of(), java.util.List.of(), null, null);
    when(orchestrator.prepareWorkflow(draft)).thenReturn(Mono.just(prepared));
    when(definitions.find("s", DRAFT_ID)).thenReturn(Mono.empty());
    final ExecutionScheduler.Admission admission =
        new ExecutionScheduler.Admission(true, Mono.empty());
    when(scheduler.acquireStart(anyString(), anyString(), anyInt(), anyString(), isNull()))
        .thenReturn(admission);
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(Mono.empty());
    final WorkflowStarter starter =
        new WorkflowStarter(
            definitions,
            cache,
            orchestrator,
            scheduler,
            new StartIdempotency(
                Duration.ofMinutes(1), Ticker.systemTicker(), mock(ExecutionLogStore.class), 10));

    StepVerifier.create(starter.startDraft("s", draft, Map.of()))
        .assertNext(id -> assertThat(id).isNotBlank())
        .verifyComplete();

    verify(scheduler).assertCanAccept("s", DRAFT_ID);
    verify(cache, never()).put(any(), any(), any(), any());
  }

  @Test
  void startClaimsItsConcurrencyKeyAndUnresolvableKeyStopsItBeforePreparing() {
    final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
    final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
    final WorkflowDefinition draft =
        new WorkflowDefinition(DRAFT_ID, "d", java.util.List.of(), java.util.List.of(), null, null);
    final ExecutionScheduler.Claims claim =
        new ExecutionScheduler.Claims(
            new ExecutionScheduler.KeyClaim("c1", 1, com.infenia.yukta.model.control.OnLimit.QUEUE),
            null);
    final com.infenia.yukta.service.runtime.AdmissionKeys keys =
        mock(com.infenia.yukta.service.runtime.AdmissionKeys.class, _ -> Mono.empty());
    when(keys.claim(draft, Map.of(CUSTOMER, "c1"))).thenReturn(Mono.just(claim));
    when(keys.claim(draft, Map.of()))
        .thenReturn(Mono.error(new IllegalArgumentException("resolves to nothing")));
    when(orchestrator.prepareWorkflow(draft)).thenReturn(Mono.just(mock(PreparedWorkflow.class)));
    when(scheduler.acquireStart(anyString(), anyString(), anyInt(), anyString(), any()))
        .thenReturn(new ExecutionScheduler.Admission(true, Mono.empty()));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(Mono.empty());
    final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
    when(definitions.find("s", DRAFT_ID)).thenReturn(Mono.empty());
    final WorkflowStarter starter =
        new WorkflowStarter(
            definitions,
            mock(PreparedWorkflowCache.class),
            orchestrator,
            scheduler,
            new StartIdempotency(
                Duration.ofMinutes(1), Ticker.systemTicker(), mock(ExecutionLogStore.class), 10));
    starter.setAdmissionKeys(keys);

    StepVerifier.create(starter.startDraft("s", draft, Map.of(CUSTOMER, "c1")))
        .expectNextCount(1)
        .verifyComplete();
    verify(scheduler)
        .acquireStart(
            org.mockito.ArgumentMatchers.eq("s"),
            org.mockito.ArgumentMatchers.eq(DRAFT_ID),
            anyInt(),
            anyString(),
            org.mockito.ArgumentMatchers.eq(claim));

    StepVerifier.create(starter.startDraft("s", draft, Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    verify(orchestrator, org.mockito.Mockito.times(1)).prepareWorkflow(draft);
  }

  @Test
  void debouncedStarts_foldIntoTheFirstRun_andOneStartThatFailsLeavesNoWindowBehind() {
    final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
    final PreparedWorkflowCache cache = mock(PreparedWorkflowCache.class);
    final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
    final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
    final WorkflowDefinition def =
        new WorkflowDefinition(
            "w",
            "d",
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            Map.of(),
            new com.infenia.yukta.model.workflow.WorkflowAdmission(
                null,
                null,
                new com.infenia.yukta.model.workflow.WorkflowAdmission.Debounce(
                    "${payload.customer}", 60_000)));
    when(definitions.find("s", "w")).thenReturn(Mono.just(def));
    when(cache.get(anyString(), anyString(), any())).thenReturn(java.util.Optional.empty());
    when(orchestrator.prepareWorkflow(def)).thenReturn(Mono.just(mock(PreparedWorkflow.class)));
    when(scheduler.acquireStart(anyString(), anyString(), anyInt(), anyString(), any()))
        .thenReturn(new ExecutionScheduler.Admission(true, Mono.empty()));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(Mono.never());
    org.mockito.Mockito.doThrow(
            new com.infenia.yukta.service.ExecutionRejectedException("Execution limit reached"))
        .doNothing()
        .when(scheduler)
        .assertCanAccept("s", "w");
    final WorkflowStarter starter =
        new WorkflowStarter(
            definitions,
            cache,
            orchestrator,
            scheduler,
            new StartIdempotency(
                Duration.ofMinutes(1), Ticker.systemTicker(), mock(ExecutionLogStore.class), 10));
    starter.setAdmissionKeys(
        new com.infenia.yukta.service.runtime.AdmissionKeys(
            definitions,
            new com.infenia.yukta.plugin.util.VariableResolver(
                mock(com.infenia.yukta.plugin.store.SecretProvider.class))));

    // the first start is refused, so its window must not swallow the next one
    StepVerifier.create(starter.start("s", "w", Map.of(CUSTOMER, "c1"), null))
        .expectError(com.infenia.yukta.service.ExecutionRejectedException.class)
        .verify();
    final String first = starter.start("s", "w", Map.of(CUSTOMER, "c1"), null).block();
    final String folded = starter.start("s", "w", Map.of(CUSTOMER, "c1"), null).block();
    final String otherKey = starter.start("s", "w", Map.of(CUSTOMER, "c2"), null).block();

    assertThat(first).isNotBlank();
    assertThat(folded).isEqualTo(first);
    assertThat(otherKey).isNotEqualTo(first);
    verify(orchestrator, org.mockito.Mockito.times(2))
        .execute(anyString(), anyString(), anyString(), any(), any(), any());
    starter.cancelAll();
  }
}
