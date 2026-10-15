// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class R2dbcKeyValueStoreTest extends PostgresTestSupport {

  private R2dbcKeyValueStore store;

  @BeforeEach
  void setUp() {
    store = new R2dbcKeyValueStore(db, new ObjectMapper());
  }

  @Test
  void putGetRemove_roundTripJsonValues() {
    assertThat(store.get("a").block()).isNull();
    assertThat(store.put("a", Map.of("k", List.of(1, "x")), 10).block())
        .isEqualTo(Map.of("k", List.of(1, "x")));
    assertThat(store.get("a").block()).isEqualTo(Map.of("k", List.of(1, "x")));
    assertThat(store.put("a", "text", 10).block()).isEqualTo("text");
    assertThat(store.remove("a").block()).isEqualTo("text");
    assertThat(store.remove("a").block()).isNull();
  }

  @Test
  void incrCountsFromZero_andConcurrentIncrementsAreNotLost() {
    assertThat(store.incr("c", 1, 10).block()).isEqualTo(1);
    assertThat(store.incr("c", 5, 10).block()).isEqualTo(6);

    Flux.range(0, 50).flatMap(i -> store.incr("c", 1, 10), 10).blockLast();

    assertThat(store.get("c").block()).isEqualTo(56);
  }

  @Test
  void incrRefusesStoredValueNotWholeNumber() {
    store.put("s", "text", 10).block();
    store.put("f", 1.5, 10).block();

    assertThatThrownBy(() -> store.incr("s", 1, 10).block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("whole number");
    assertThatThrownBy(() -> store.incr("f", 1, 10).block())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void newKeysBeyondTheCapAreRefused_butExistingOnesCanChange() {
    store.put("a", 1, 1).block();

    assertThatThrownBy(() -> store.put("b", 1, 1).block())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("full");
    assertThatThrownBy(() -> store.incr("c", 1, 1).block())
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.put("a", 2, 1).block()).isEqualTo(2);
    assertThat(store.incr("a", 1, 1).block()).isEqualTo(3);
  }
}
