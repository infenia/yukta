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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** A resumed run takes over the open request of a run that is gone, and only of one that is. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class InputBrokerTakeOverTest {

  private final InMemoryInputRequestStore memory = new InMemoryInputRequestStore(100);
  private final InputRequestStore store = spy(memory);
  private final ExecutionControlStore runs = mock(ExecutionControlStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final InputBroker broker =
      InputBrokerFixture.of(
          store,
          new ExecutionEventFeed(),
          new InMemoryExecutionLogStore(),
          meters,
          new InputRedactor(List.of()),
          limits(),
          new MutableClock());

  private InputRequest onlyOpen() {
    return java.util.Objects.requireNonNull(broker.listOpen(null, null).collectList().block())
        .getFirst();
  }

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
  void resumedRunTakesOverTheRequestOfGoneRun() {
    broker.setExecutionControlStore(runs);
    when(runs.findByExecutionId("e1")).thenReturn(Optional.empty());
    broker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest first = onlyOpen();
    final List<InputResponse> answers = new ArrayList<>();

    broker.ask(who("e2", "n1"), spec("k1")).subscribe(answers::add);

    final InputRequest taken = onlyOpen();
    assertThat(taken.id()).isEqualTo(first.id());
    assertThat(taken.executionId()).isEqualTo("e2");
    assertThat(taken.version()).isEqualTo(first.version() + 1);
    assertThat(meters.counter("yukta.input.taken-over").count()).isEqualTo(1);
    broker.answer(taken.id(), null, answer(taken)).block();
    assertThat(answers).hasSize(1);
  }

  @Test
  void liveRunKeepsItsRequest() {
    broker.setExecutionControlStore(runs);
    when(runs.findByExecutionId("e1")).thenReturn(Optional.of(mock(ExecutionControl.class)));
    broker.ask(who("e1", "n1"), spec("k1")).subscribe();

    broker.ask(who("e2", "n1"), spec("k1")).subscribe();

    assertThat(onlyOpen().executionId()).isEqualTo("e1");
  }

  @Test
  void sameRunAskingAgainIsNoTakeover() {
    broker.setExecutionControlStore(runs);
    broker.ask(who("e1", "n1"), spec("k1")).subscribe();

    broker.ask(who("e1", "n1"), spec("k1")).subscribe();

    assertThat(onlyOpen().version()).isZero();
  }

  @Test
  void withoutLiveRunStoreRequestStaysWithItsRun() {
    broker.ask(who("e1", "n1"), spec("k1")).subscribe();

    broker.ask(who("e2", "n1"), spec("k1")).subscribe();

    assertThat(onlyOpen().executionId()).isEqualTo("e1");
  }

  @Test
  void whenAnotherChangeWinsTheRaceTheRequestAsItIsNowIsUsed() {
    broker.setExecutionControlStore(runs);
    when(runs.findByExecutionId("e1")).thenReturn(Optional.empty());
    broker.ask(who("e1", "n1"), spec("k1")).subscribe();
    doReturn(Mono.just(false)).when(store).compareAndSet(anyString(), anyLong(), any());

    broker.ask(who("e2", "n1"), spec("k1")).subscribe();

    assertThat(onlyOpen().executionId()).isEqualTo("e1");
    assertThat(meters.find("yukta.input.taken-over").counter()).isNull();
  }
}
