// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.testrun.TestRuns;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UnitTestShouldIncludeAssert"})
class TestRunFilteringLogStoreTest {

  private final ExecutionLogStore delegate = mock(ExecutionLogStore.class);
  private final TestRunFilteringLogStore store = new TestRunFilteringLogStore(delegate);

  private static ExecutionLogEvent event(final String workflowId) {
    final ExecutionLogEvent event = mock(ExecutionLogEvent.class);
    when(event.workflowId()).thenReturn(workflowId);
    return event;
  }

  @Test
  void aTestRunEventIsDropped() {
    store.append(event(TestRuns.newWorkflowId()));

    verify(delegate, never()).append(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void anyOtherEventIsKept() {
    final ExecutionLogEvent event = event("wf");

    store.append(event);

    verify(delegate).append(event);
  }

  @Test
  void everyReadGoesToTheWrappedStore() {
    final ExecutionLogEvent event = event("wf");
    final Instant since = Instant.EPOCH;
    final Set<ExecutionLogEvent.Type> types = Set.of(ExecutionLogEvent.Type.RUN_STARTED);
    final ExecutionLogStore.RunHeader header = new ExecutionLogStore.RunHeader("s", "wf");
    when(delegate.readAll()).thenReturn(Flux.just(event));
    when(delegate.forExecution("e")).thenReturn(Flux.just(event));
    when(delegate.header("e")).thenReturn(Mono.just(header));
    when(delegate.ofType(ExecutionLogEvent.Type.RUN_STARTED, since)).thenReturn(Flux.just(event));
    when(delegate.newest(types, "e", 3)).thenReturn(Flux.just(event));
    when(delegate.replaceAll(List.of(event))).thenReturn(Mono.empty());

    StepVerifier.create(store.readAll()).expectNext(event).verifyComplete();
    StepVerifier.create(store.forExecution("e")).expectNext(event).verifyComplete();
    StepVerifier.create(store.header("e")).expectNext(header).verifyComplete();
    StepVerifier.create(store.ofType(ExecutionLogEvent.Type.RUN_STARTED, since))
        .expectNext(event)
        .verifyComplete();
    StepVerifier.create(store.newest(types, "e", 3)).expectNext(event).verifyComplete();
    StepVerifier.create(store.replaceAll(List.of(event))).verifyComplete();
  }
}
