// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.spec;
import static com.infenia.yukta.service.input.InputTestSupport.who;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** What happens to input requests when something around them fails or falls behind. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class InputRequestResilienceTest {

  private final InputRequestStore store = spy(new InMemoryInputRequestStore(1000));
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final InputBroker broker =
      new InputBroker(
          store,
          new ExecutionEventFeed(),
          new InMemoryExecutionLogStore(),
          meters,
          new InputRedactor(List.of()),
          limits(),
          new MutableClock());

  private static InputResponse answer(final InputRequest request) {
    return new InputResponse(
        request.id(),
        "go",
        null,
        Map.of(),
        new Responder(Responder.Type.HUMAN, "ann"),
        null,
        null,
        Instant.parse("2026-11-02T10:00:01Z"));
  }

  @Test
  void storeFailingDuringAnswerLeavesTheRequestOpenAndRetrySucceeds() {
    final AtomicReference<InputResponse> waiting = new AtomicReference<>();
    broker.ask(who("e1", "n1"), spec("k1")).subscribe(waiting::set);
    final InputRequest request =
        java.util.Objects.requireNonNull(broker.listOpen(null, null).blockFirst());
    doReturn(Mono.error(new IllegalStateException("store down")))
        .doCallRealMethod()
        .when(store)
        .compareAndSet(anyString(), anyLong(), any());

    StepVerifier.create(broker.answer(request.id(), null, answer(request)))
        .expectErrorMessage("store down")
        .verify();

    // nothing changed: the request is open, the caller still waits, and no answer is stored
    assertThat(java.util.Objects.requireNonNull(broker.find(request.id()).block()).state())
        .isEqualTo(InputState.OPEN);
    assertThat(waiting.get()).isNull();
    assertThat(store.findResponse(request.id()).block()).isNull();

    StepVerifier.create(broker.answer(request.id(), null, answer(request)))
        .assertNext(done -> assertThat(done.state()).isEqualTo(InputState.ANSWERED))
        .verifyComplete();
    assertThat(waiting.get().decision()).isEqualTo("go");
  }

  @Test
  void supervisorThatMissedEventsFindsEveryOpenRequestByListing() {
    // nobody reads the feed here: the open requests are the store's, not the feed's
    for (int i = 0; i < 40; i++) {
      broker.ask(who("e" + i, "n1"), spec("k" + i)).subscribe();
    }

    final List<InputRequest> listed = broker.listOpen(null, null).collectList().block();

    assertThat(listed).hasSize(40).allMatch(request -> request.state() == InputState.OPEN);
  }

  @Test
  void everySweepIsTimed() {
    broker.sweep().block();
    broker.sweep().block();

    assertThat(meters.timer("yukta.input.sweep").count()).isEqualTo(2);
  }
}
