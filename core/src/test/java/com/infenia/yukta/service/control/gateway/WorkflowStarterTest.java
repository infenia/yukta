// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
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
}
