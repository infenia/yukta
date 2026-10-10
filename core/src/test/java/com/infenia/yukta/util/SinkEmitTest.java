// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ArrayBlockingQueue;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Sinks;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class SinkEmitTest {

  @Test
  void offer_acceptedElement_returnsTrue() {
    final Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

    assertThat(SinkEmit.offer(sink, "a")).isTrue();
  }

  @Test
  void offer_fullSink_returnsFalse_andLeavesTheSinkUsable() {
    final Sinks.Many<String> sink =
        Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(1));
    sink.asFlux()
        .subscribe(
            new BaseSubscriber<>() {
              @Override
              protected void hookOnSubscribe(final org.reactivestreams.Subscription subscription) {
                // never request, so the queue fills up
              }
            });

    assertThat(SinkEmit.offer(sink, "a")).isTrue();
    assertThat(SinkEmit.offer(sink, "b")).isFalse();
    assertThat(sink.tryEmitNext("c")).isNotEqualTo(Sinks.EmitResult.FAIL_TERMINATED);
  }

  @Test
  @SuppressWarnings("unchecked")
  void offer_retriesWhileAnotherThreadEmits() {
    final Sinks.Many<String> sink = mock(Sinks.Many.class);
    when(sink.tryEmitNext("a"))
        .thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED, Sinks.EmitResult.OK);

    assertThat(SinkEmit.offer(sink, "a")).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void offer_givesUpWhenContentionNeverEnds() {
    final Sinks.Many<String> sink = mock(Sinks.Many.class);
    when(sink.tryEmitNext("a")).thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED);

    assertThat(SinkEmit.offer(sink, "a")).isFalse();
  }
}
