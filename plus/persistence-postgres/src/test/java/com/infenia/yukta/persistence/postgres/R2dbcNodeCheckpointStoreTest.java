// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
@ExpectedErrorLog({
  "ERROR com.infenia.yukta.persistence.postgres.Fencing - ",
  "ERROR com.infenia.yukta.persistence.postgres.R2dbcNodeCheckpointStore - "
})
class R2dbcNodeCheckpointStoreTest extends PostgresTestSupport {

  private R2dbcNodeCheckpointStore store;

  @BeforeEach
  void setUp() {
    store = new R2dbcNodeCheckpointStore(db, new ObjectMapper());
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void messagesComeBackInOrder_andTheLastOneIsTheCheckpoint() {
    store.save("e1", "n1", message("a")).block();
    store.save("e1", "n1", message(Map.of("k", 1))).block();

    assertThat(store.getAll("e1", "n1").<Object>map(Message::payload).collectList().block())
        .containsExactly("a", Map.of("k", 1));
    assertThat(store.get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
    assertThat(store.getAll("e1", "other").collectList().block()).isEmpty();
    assertThat(store.bytesHeld()).isPositive();
  }

  @Test
  void nodeIsFinishedOnlyAfterMarker() {
    store.save("e1", "n1", message("a")).block();
    assertThat(store.isComplete("e1", "n1")).isTrue();
    assertThat(store.isFinished("e1", "n1")).isFalse();

    store.markComplete("e1", "n1").block();

    assertThat(store.isFinished("e1", "n1")).isTrue();
    assertThat(store.isFinished("e1", "unknown")).isFalse();
  }

  @Test
  void unwritableMessage_marksNodeIncomplete() {
    final ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("cannot write"));
    final R2dbcNodeCheckpointStore failing = new R2dbcNodeCheckpointStore(db, broken);

    failing.save("e1", "n1", message("a")).block();
    failing.markComplete("e1", "n1").block();

    assertThat(failing.isComplete("e1", "n1")).isFalse();
    assertThat(failing.isFinished("e1", "n1")).isFalse();
  }

  @Test
  void clearRemovesTheRun_andFreesTheCountedBytes() {
    store.save("e1", "n1", message("a")).block();
    store.markComplete("e1", "n1").block();
    store.save("e2", "n1", message("b")).block();

    store.clear("e1");

    assertThat(store.getAll("e1", "n1").collectList().block()).isEmpty();
    assertThat(store.isFinished("e1", "n1")).isFalse();
    assertThat(store.getAll("e2", "n1").collectList().block()).hasSize(1);
  }

  @Test
  void unreachableDatabase_neverFailsTheCaller_andNothingIsFinished() {
    db.sql("ALTER TABLE node_checkpoint_state RENAME TO node_checkpoint_state_gone")
        .fetch()
        .rowsUpdated()
        .block();
    try {
      store.save("e1", "n1", message("a")).block();
      store.markComplete("e1", "n1").block();
      assertThat(store.isFinished("e1", "n1")).isFalse();
      assertThat(store.isComplete("e1", "n1")).isFalse();
      store.clear("e1");
      Thread.currentThread().interrupt();
      store.clear("e1");
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      db.sql("ALTER TABLE node_checkpoint_state_gone RENAME TO node_checkpoint_state")
          .fetch()
          .rowsUpdated()
          .block();
    }
  }

  @Test
  void interruptedCaller_treatsTheNodeAsUnfinished() {
    store.markComplete("e1", "n1").block();
    Thread.currentThread().interrupt();
    try {
      assertThat(store.isFinished("e1", "n1")).isFalse();
      assertThat(Thread.interrupted()).isTrue();
      Thread.currentThread().interrupt();
      assertThat(store.isComplete("e1", "n1")).isFalse();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void staleLeader_saveIsRefusedAndCounted() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcNodeCheckpointStore fenced =
        new R2dbcNodeCheckpointStore(db, new ObjectMapper(), meters, () -> 1);
    db.sql(
            "INSERT INTO leader_lease (name, holder, fencing, expires_at)"
                + " VALUES ('leader', 'other', 1, now() + interval '1 hour')")
        .fetch()
        .rowsUpdated()
        .block();
    fenced.save("e1", "n1", message("a")).block();
    db.sql("UPDATE leader_lease SET fencing = 2").fetch().rowsUpdated().block();

    fenced.save("e1", "n1", message("b")).block();

    assertThat(fenced.getAll("e1", "n1").<Object>map(Message::payload).collectList().block())
        .containsExactly("a");
    assertThat(fenced.isComplete("e1", "n1")).isTrue();
    assertThat(meters.get("yukta.fencing.refused").counter().count()).isEqualTo(1);
  }
}
