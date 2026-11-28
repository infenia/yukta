// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.response.ActivityHistoryEntry;
import com.infenia.yukta.service.activity.ActivityEntry;
import com.infenia.yukta.service.activity.InMemoryActivityStore;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests for {@link ActivityHistoryController}. */
@NoArgsConstructor
class ActivityHistoryControllerTest {

  private static ActivityHistoryController controller() {
    final InMemoryActivityStore store = new InMemoryActivityStore();
    store
        .append(
            List.of(
                entry("WorkflowStatusChanged", "INFO", "e1"),
                entry("NodeStatusChanged", "ERROR", "e2"),
                entry("WorkflowStatusChanged", "ERROR", "e3")))
        .block();
    return new ActivityHistoryController(store, JsonMapper.builder().build());
  }

  private static ActivityEntry entry(final String type, final String severity, final String run) {
    return new ActivityEntry(
        0, Instant.parse("2026-11-28T10:00:00Z"), type, severity, run, "{\"run\":\"" + run + "\"}");
  }

  private static List<ActivityHistoryEntry> list(
      final Integer limit, final Long before, final String type, final String severity) {
    return java.util.Objects.requireNonNull(
            controller().history(limit, before, type, severity).block())
        .data();
  }

  @Test
  void returnsTheNewestFirstAsTheStreamEnvelope() {
    final var entries = list(100, null, null, null);

    assertThat(entries)
        .extracting(e -> e.envelope().get("run").asString())
        .containsExactly("e3", "e2", "e1");
  }

  @Test
  void beforeAndLimitPageBack() {
    final var first = list(2, null, null, null);
    final var older = list(2, first.getLast().seq(), null, null);

    assertThat(older).extracting(e -> e.envelope().get("run").asString()).containsExactly("e1");
  }

  @Test
  void typeKeepsItsCaseAndSeverityIsUpperCased() {
    assertThat(list(100, null, "WorkflowStatusChanged", "error"))
        .extracting(e -> e.envelope().get("run").asString())
        .containsExactly("e3");
    assertThat(list(100, null, " ", null)).hasSize(3);
  }

  @Test
  void limitIsClamped() {
    assertThat(list(0, null, null, null)).hasSize(1);
  }
}
