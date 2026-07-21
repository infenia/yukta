// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionListItems DTO. */
@NoArgsConstructor
final class SessionListItemsTest {

  @Test
  void testSessionListItemsWithMultipleItems() {
    final List<SessionListItem> items =
        List.of(
            new SessionListItem("sess-1", "Session 1", "First", "user1", List.of("prod"), "/p1", 2),
            new SessionListItem(
                "sess-2", "Session 2", "Second", "user2", List.of("dev"), "/p2", 1));

    final SessionListItems wrapper = new SessionListItems(items);

    assertThat(wrapper).isNotNull();
    assertThat(wrapper.sessions()).hasSize(2);
    assertThat(wrapper.sessions().getFirst().sessionId()).isEqualTo("sess-1");
    assertThat(wrapper.sessions().getLast().sessionId()).isEqualTo("sess-2");
  }

  @Test
  void testSessionListItemsWithEmptyList() {
    final List<SessionListItem> emptyItems = List.of();
    final SessionListItems wrapper = new SessionListItems(emptyItems);

    assertThat(wrapper).isNotNull();
    assertThat(wrapper.sessions()).isEmpty();
  }

  @Test
  void testSessionListItemsWithNullList() {
    final SessionListItems wrapper = new SessionListItems(null);

    assertThat(wrapper).isNotNull();
    assertThat(wrapper.sessions()).isEmpty();
  }

  @Test
  void testSessionListItemsWithSingleItem() {
    final List<SessionListItem> items =
        List.of(new SessionListItem("sess-only", "Only Session", "Solo", "bot", null, "/solo", 0));

    final SessionListItems wrapper = new SessionListItems(items);

    assertThat(wrapper).isNotNull();
    assertThat(wrapper.sessions()).hasSize(1);
    assertThat(wrapper.sessions().getFirst().sessionId()).isEqualTo("sess-only");
  }
}
