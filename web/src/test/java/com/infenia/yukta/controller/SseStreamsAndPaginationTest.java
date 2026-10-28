// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.api.ApiResponse;
import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link Pagination}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.LawOfDemeter",
})
class SseStreamsAndPaginationTest {

  @Test
  void testEventsGetSequentialIds() {
    StepVerifier.create(SseStreams.events(Flux.just("a", "b"), null, Duration.ofSeconds(30)))
        .assertNext(e -> assertThat(e.id()).isEqualTo("0"))
        .assertNext(e -> assertThat(e.id()).isEqualTo("1"))
        .verifyComplete();
  }

  @Test
  void testLastEventIdSkipsAlreadyDeliveredEvents() {
    StepVerifier.create(SseStreams.events(Flux.just("a", "b", "c"), "1", Duration.ofSeconds(30)))
        .assertNext(e -> assertThat(e.data()).isEqualTo("c"))
        .verifyComplete();
  }

  @Test
  void testGarbageLastEventIdIsIgnored() {
    StepVerifier.create(SseStreams.events(Flux.just("a"), "not-a-number", Duration.ofSeconds(30)))
        .assertNext(e -> assertThat(e.data()).isEqualTo("a"))
        .verifyComplete();
  }

  @Test
  void testIdleStreamSendsKeepAliveComments() {
    final Flux<ServerSentEvent<String>> events =
        SseStreams.events(Flux.<String>never(), null, Duration.ofMillis(20));

    StepVerifier.create(events.take(2))
        .assertNext(e -> assertThat(e.comment()).isEqualTo("ping"))
        .assertNext(e -> assertThat(e.comment()).isEqualTo("ping"))
        .verifyComplete();
  }

  @Test
  void testKeepAliveStopsWhenDataEnds() {
    StepVerifier.create(
            SseStreams.events(
                Flux.just("a").delayElements(Duration.ofMillis(50)), null, Duration.ofMillis(10)))
        .thenConsumeWhile(e -> true)
        .verifyComplete();
  }

  @Test
  void testPageClampsLimitAndAnnouncesNextCursor() {
    final List<String> all = List.of("a", "b", "c");

    final ResponseEntity<ApiResponse<List<String>>> first =
        Pagination.page(all, 2, null, id -> id, "m");
    assertThat(first.getBody().data()).containsExactly("a", "b");
    assertThat(first.getHeaders().getFirst(Pagination.NEXT_CURSOR)).isEqualTo("b");

    final var last = Pagination.page(all, 2, "b", id -> id, "m");
    assertThat(last.getBody().data()).containsExactly("c");
    assertThat(last.getHeaders().containsHeader(Pagination.NEXT_CURSOR)).isFalse();

    assertThat(Pagination.page(all, 0, null, id -> id, "m").getBody().data()).hasSize(3);
    assertThat(Pagination.page(all, 99_999, null, id -> id, "m").getBody().data()).hasSize(3);
  }

  @Test
  void testUnknownCursorIsRefused() {
    assertThatThrownBy(() -> Pagination.page(List.of("a", "b"), 5, "zzz", id -> id, "m"))
        .isInstanceOf(ServerWebInputException.class)
        .hasMessageContaining("Unknown cursor: zzz");
  }
}
