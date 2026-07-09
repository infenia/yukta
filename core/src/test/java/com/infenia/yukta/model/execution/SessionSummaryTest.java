// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link SessionSummary}. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ShortVariable", "PMD.UseConcurrentHashMap"})
@NoArgsConstructor
class SessionSummaryTest {

  @Test
  void shouldCreateSessionSummaryWithAllFields() {
    final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
    final Map<String, String> tags = Map.of("env", "prod");
    final SessionSummary summary =
        new SessionSummary("s1", "user1", "2026-03-10T10:00:00Z", now, "desc", tags);

    assertThat(summary.sessionId()).isEqualTo("s1");
    assertThat(summary.initiator()).isEqualTo("user1");
    assertThat(summary.initiatedTime()).isEqualTo("2026-03-10T10:00:00Z");
    assertThat(summary.lastActiveTime()).isEqualTo(now);
    assertThat(summary.description()).isEqualTo("desc");
    assertThat(summary.tags()).isEqualTo(tags);
  }

  @Test
  void shouldHandleNullTags() {
    final SessionSummary summary = new SessionSummary("s1", "user1", "time", null, "desc", null);
    assertThat(summary.tags()).isNotNull().isEmpty();
  }

  @Test
  void shouldCreateImmutableCopyOfTags() {
    final Map<String, String> mutableTags = new HashMap<>();
    mutableTags.put("key", "value");

    final SessionSummary summary =
        new SessionSummary("s1", "user1", "time", null, "desc", mutableTags);
    mutableTags.put("key2", "value2");

    assertThat(summary.tags()).hasSize(1).containsEntry("key", "value");
    assertThrows(UnsupportedOperationException.class, () -> summary.tags().put("new", "val"));
  }

  @Test
  void testEqualsAndHashCode() {
    final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
    final Map<String, String> tags = Map.of("a", "b");
    final SessionSummary s1 = new SessionSummary("id", "init", "time", now, "desc", tags);
    final SessionSummary s2 = new SessionSummary("id", "init", "time", now, "desc", tags);
    final SessionSummary s3 = new SessionSummary("id2", "init", "time", now, "desc", tags);

    assertThat(s1).isEqualTo(s2).hasSameHashCodeAs(s2);
    assertThat(s1).isNotEqualTo(s3);
    assertThat(s1.hashCode()).isNotEqualTo(s3.hashCode());
  }

  @Test
  void testToString() {
    final SessionSummary summary =
        new SessionSummary("id", "init", "time", null, "desc", Map.of("k", "v"));
    final String toString = summary.toString();
    assertThat(toString)
        .contains("id")
        .contains("init")
        .contains("time")
        .contains("desc")
        .contains("k=v");
  }
}
