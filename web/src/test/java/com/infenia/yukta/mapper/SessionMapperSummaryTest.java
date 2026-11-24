// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.response.SessionListItem;
import com.infenia.yukta.model.session.SessionSummary;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Tests for the SessionSummary mapping of SessionMapper. */
class SessionMapperSummaryTest {

  /** Mapper for session summary transformation. */
  private SessionMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = Mappers.getMapper(SessionMapper.class);
  }

  @Test
  void testSummaryToSessionListItemMapsFieldsAndTags() {
    final SessionSummary summary =
        new SessionSummary(
            "sess-sum",
            "Summary Session",
            "Summary Description",
            "owner",
            Map.of("env", "prod"),
            "/summary/path",
            4);

    final SessionListItem result = mapper.summaryToSessionListItem(summary);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("sess-sum");
    assertThat(result.name()).isEqualTo("Summary Session");
    assertThat(result.description()).isEqualTo("Summary Description");
    assertThat(result.initiator()).isEqualTo("owner");
    assertThat(result.projectPath()).isEqualTo("/summary/path");
    assertThat(result.tags()).containsExactly("env");
    assertThat(result.workflowCount()).isEqualTo(4);
    assertThat(result.lastRun()).isNull();
  }

  @Test
  void testSummaryToSessionListItemWithNoTags() {
    final SessionSummary summary =
        new SessionSummary("sess-empty", "Empty", "", "system", Map.of(), "/p", 0);

    final SessionListItem result = mapper.summaryToSessionListItem(summary);

    assertThat(result.tags()).isEmpty();
    assertThat(result.workflowCount()).isZero();
  }

  @Test
  void testSummaryToSessionListItemWithNull() {
    assertThat(mapper.summaryToSessionListItem(null)).isNull();
  }
}
