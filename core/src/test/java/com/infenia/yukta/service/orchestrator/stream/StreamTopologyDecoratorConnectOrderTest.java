// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * A node's output must reach its child even when the connectors run before the child subscribes.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.UseShortArrayInitializer")
class StreamTopologyDecoratorConnectOrderTest {

  private final NodeCheckpointStore store = Mockito.mock(NodeCheckpointStore.class);
  private final StreamTopologyDecorator decorator =
      new StreamTopologyDecorator(Mockito.mock(DefaultTaskTrackerService.class), store);

  @Test
  void triggerIntoProcessorIntoTerminal_deliversTheMessageWhenConnectorsRunFirst() {
    Mockito.when(store.save(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenReturn(Mono.empty());
    Mockito.when(store.markComplete(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(Mono.empty());
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();
    final Message<?> message = DefaultMessage.create(UUID.randomUUID(), "payload");

    // Assembly order: the trigger, then the processor reading it, then the terminal reading that.
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] trigger =
        new Flux[] {
          decorator.applyLoggingAndBroadcasting(
              "exec", "trigger", Flux.just(message), 16, disposables, connectors)
        };
    final Flux<Message<?>> processorInput =
        decorator.mergeParentStreams(trigger, new ParentEdgeInfo(0, "trigger", null));
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] processor =
        new Flux[] {
          decorator.applyLoggingAndBroadcasting(
              "exec", "processor", processorInput, 16, disposables, connectors)
        };
    final Flux<Message<?>> terminalInput =
        decorator.mergeParentStreams(processor, new ParentEdgeInfo(0, "processor", null));

    // The tail's doOnSubscribe runs the connectors in reverse order, as the compiler does.
    final Flux<Message<?>> tail =
        terminalInput.doOnSubscribe(
            _ -> {
              for (int i = connectors.size() - 1; i >= 0; i--) {
                connectors.get(i).run();
              }
            });

    // A deadline turns a stalled stream into a failure instead of a hung build.
    StepVerifier.create(tail).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
    disposables.forEach(Disposable::dispose);
  }
}
