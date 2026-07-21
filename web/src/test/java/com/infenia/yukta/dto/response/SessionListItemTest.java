// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionListItem DTO. */
@NoArgsConstructor
final class SessionListItemTest {

  @Test
  void testSessionListItemWithTags() {
    final List<String> tags = List.of("prod", "etl", "critical");
    final SessionListItem item =
        new SessionListItem(
            "sess-123", "Production ETL", "Nightly batch", "scheduler", tags, "/data/main", 5);

    assertThat(item).isNotNull();
    assertThat(item.sessionId()).isEqualTo("sess-123");
    assertThat(item.name()).isEqualTo("Production ETL");
    assertThat(item.description()).isEqualTo("Nightly batch");
    assertThat(item.initiator()).isEqualTo("scheduler");
    assertThat(item.tags()).containsExactly("prod", "etl", "critical");
    assertThat(item.projectPath()).isEqualTo("/data/main");
    assertThat(item.workflowCount()).isEqualTo(5);
  }

  @Test
  void testSessionListItemWithNullTags() {
    final SessionListItem item =
        new SessionListItem(
            "sess-456", "Test Session", "Test Description", "user", null, "/home/project", 0);

    assertThat(item).isNotNull();
    assertThat(item.sessionId()).isEqualTo("sess-456");
    assertThat(item.name()).isEqualTo("Test Session");
    assertThat(item.tags()).isEmpty();
  }

  @Test
  void testSessionListItemWithEmptyTags() {
    final List<String> emptyTags = List.of();
    final SessionListItem item =
        new SessionListItem(
            "sess-789", "Empty Tags Session", "Has empty list", "admin", emptyTags, "/var/app", 3);

    assertThat(item).isNotNull();
    assertThat(item.tags()).isEmpty();
  }
}
