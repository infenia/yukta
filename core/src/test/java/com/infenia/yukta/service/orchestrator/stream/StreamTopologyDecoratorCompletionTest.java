// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The end of a node is made durable once, after its output, before the completion goes on. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
@ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator - ")
class StreamTopologyDecoratorCompletionTest {

  private final NodeCheckpointStore store = mock(NodeCheckpointStore.class);
  private final StreamTopologyDecorator decorator =
      new StreamTopologyDecorator(mock(DefaultTaskTrackerService.class), store);
  private final List<Disposable> disposables = new ArrayList<>();

  private Flux<Message<?>> decorate(final Flux<Message<?>> input) {
    return decorateWith(com.infenia.yukta.model.workflow.NodePolicy.Checkpoint.ALL, input);
  }

  private Flux<Message<?>> decorateWith(
      final com.infenia.yukta.model.workflow.NodePolicy.Checkpoint checkpoint,
      final Flux<Message<?>> input) {
    when(store.save(anyString(), anyString(), any())).thenReturn(Mono.empty());
    when(store.saveAll(anyString(), anyString(), any())).thenCallRealMethod();
    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(
            "e", "n", input, 16, disposables, new ArrayList<>(), () -> false, checkpoint);
    return decorator.applyEdgeRouting(
        new Flux[] {output}, new com.infenia.yukta.model.workflow.ParentEdgeInfo(0, "n", null));
  }

  private static Message<?> message() {
    return DefaultMessage.create(UUID.randomUUID(), "x");
  }

  @Test
  void normalCompletion_marksTheNodeAfterItsLastSave_andBeforeCompletionReachesTheSubscriber() {
    final AtomicBoolean marked = new AtomicBoolean();
    when(store.markComplete("e", "n")).thenReturn(Mono.fromRunnable(() -> marked.set(true)));

    StepVerifier.create(decorate(Flux.just(message(), message())))
        .expectNextCount(2)
        .then(() -> assertThat(marked).isTrue())
        .verifyComplete();

    final InOrder order = inOrder(store);
    order.verify(store, org.mockito.Mockito.times(2)).save(anyString(), anyString(), any());
    order.verify(store).markComplete("e", "n");
  }

  @Test
  void failedNode_isNeverMarked() {
    StepVerifier.create(
            decorate(
                Flux.concat(Flux.just(message()), Flux.error(new IllegalStateException("boom")))))
        .expectNextCount(1)
        .expectError(IllegalStateException.class)
        .verify();

    verify(store, never()).markComplete(anyString(), anyString());
  }

  @Test
  void cancelledNode_isNeverMarked() {
    StepVerifier.create(decorate(Flux.concat(Flux.just(message()), Flux.never())))
        .expectNextCount(1)
        .thenCancel()
        .verify();

    verify(store, never()).markComplete(anyString(), anyString());
  }

  @Test
  void storeThatCannotMark_doesNotFailTheRun() {
    when(store.markComplete("e", "n"))
        .thenReturn(Mono.error(new IllegalStateException("disk full")));

    StepVerifier.create(decorate(Flux.just(message()))).expectNextCount(1).verifyComplete();
  }

  @Test
  void storeThatCannotSave_doesNotFailTheRun_butLeavesTheNodeUnmarked() {
    final Flux<Message<?>> out = decorate(Flux.just(message(), message()));
    when(store.save(anyString(), anyString(), any()))
        .thenReturn(Mono.error(new IllegalStateException("disk full")));

    StepVerifier.create(out).expectNextCount(2).verifyComplete();

    verify(store, never()).markComplete(anyString(), anyString());
  }

  @Test
  void lastCheckpoint_savesOnlyTheFinalMessage_andMarksTheNode() {
    when(store.markComplete("e", "n")).thenReturn(Mono.empty());

    StepVerifier.create(
            decorateWith(
                com.infenia.yukta.model.workflow.NodePolicy.Checkpoint.LAST,
                Flux.just(message(), message())))
        .expectNextCount(2)
        .verifyComplete();

    verify(store, times(1)).save(anyString(), anyString(), any());
    verify(store, never()).saveAll(anyString(), anyString(), any());
    verify(store).markComplete("e", "n");
  }

  @Test
  void lastCheckpoint_withNoMessages_savesNothing_butMarksTheNode() {
    when(store.markComplete("e", "n")).thenReturn(Mono.empty());

    StepVerifier.create(
            decorateWith(com.infenia.yukta.model.workflow.NodePolicy.Checkpoint.LAST, Flux.empty()))
        .verifyComplete();

    verify(store, never()).save(anyString(), anyString(), any());
    verify(store).markComplete("e", "n");
  }

  @Test
  void lastCheckpoint_thatCannotSave_leavesTheNodeUnmarked() {
    final Flux<Message<?>> out =
        decorateWith(
            com.infenia.yukta.model.workflow.NodePolicy.Checkpoint.LAST, Flux.just(message()));
    when(store.save(anyString(), anyString(), any()))
        .thenReturn(Mono.error(new IllegalStateException("disk full")));

    StepVerifier.create(out).expectNextCount(1).verifyComplete();

    verify(store, never()).markComplete(anyString(), anyString());
  }

  @Test
  void noneCheckpoint_writesNothing_andNodeIsNeverMarked() {
    StepVerifier.create(
            decorateWith(
                com.infenia.yukta.model.workflow.NodePolicy.Checkpoint.NONE,
                Flux.just(message(), message())))
        .expectNextCount(2)
        .verifyComplete();

    verify(store, never()).save(anyString(), anyString(), any());
    verify(store, never()).saveAll(anyString(), anyString(), any());
    verify(store, never()).markComplete(anyString(), anyString());
  }
}
