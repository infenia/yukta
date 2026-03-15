/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.model.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionSummaryTest {

  @Test
  void shouldCreateSessionSummaryWithAllFields() {
    LocalDateTime now = LocalDateTime.now();
    Map<String, String> tags = Map.of("env", "prod");
    SessionSummary summary =
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
    SessionSummary summary = new SessionSummary("s1", "user1", "time", null, "desc", null);
    assertThat(summary.tags()).isNotNull().isEmpty();
  }

  @Test
  void shouldCreateImmutableCopyOfTags() {
    Map<String, String> mutableTags = new HashMap<>();
    mutableTags.put("key", "value");

    SessionSummary summary = new SessionSummary("s1", "user1", "time", null, "desc", mutableTags);
    mutableTags.put("key2", "value2");

    assertThat(summary.tags()).hasSize(1).containsEntry("key", "value");
    assertThrows(UnsupportedOperationException.class, () -> summary.tags().put("new", "val"));
  }

  @Test
  void testEqualsAndHashCode() {
    LocalDateTime now = LocalDateTime.now();
    Map<String, String> tags = Map.of("a", "b");
    SessionSummary s1 = new SessionSummary("id", "init", "time", now, "desc", tags);
    SessionSummary s2 = new SessionSummary("id", "init", "time", now, "desc", tags);
    SessionSummary s3 = new SessionSummary("id2", "init", "time", now, "desc", tags);

    assertThat(s1).isEqualTo(s2).hasSameHashCodeAs(s2);
    assertThat(s1).isNotEqualTo(s3);
    assertThat(s1.hashCode()).isNotEqualTo(s3.hashCode());
  }

  @Test
  void testToString() {
    SessionSummary summary =
        new SessionSummary("id", "init", "time", null, "desc", Map.of("k", "v"));
    String toString = summary.toString();
    assertThat(toString)
        .contains("id")
        .contains("init")
        .contains("time")
        .contains("desc")
        .contains("k=v");
  }
}
