// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

class WorkflowStarterTest {

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
  void draftIsPreparedAndRunWithoutBeingLoadedOrCached() {
    final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
    final PreparedWorkflowCache cache = mock(PreparedWorkflowCache.class);
    final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
    final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
    final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
    final WorkflowDefinition draft =
        new WorkflowDefinition(
            "__test__.x", "d", java.util.List.of(), java.util.List.of(), null, null);
    when(orchestrator.prepareWorkflow(draft)).thenReturn(Mono.just(prepared));
    final ExecutionScheduler.Admission admission =
        new ExecutionScheduler.Admission(true, Mono.empty());
    when(scheduler.acquireStart(anyString(), anyString(), anyInt(), anyString()))
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

    verify(scheduler).assertCanAccept("s", "__test__.x");
    verify(definitions, never()).find(any(), any());
    verify(cache, never()).put(any(), any(), any(), any());
  }
}
