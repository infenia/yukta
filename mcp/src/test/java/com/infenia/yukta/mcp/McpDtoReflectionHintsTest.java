// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.mcp.dto.SessionList;
import com.infenia.yukta.mcp.dto.WorkflowVersions;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/** The MCP result records stay readable by reflection in a native image. */
@NoArgsConstructor
class McpDtoReflectionHintsTest {

  @Test
  void registerHints_dtoRecords_areReadableByReflection() {
    final RuntimeHints hints = new RuntimeHints();

    new McpDtoReflectionHints.Hints()
        .registerHints(hints, Thread.currentThread().getContextClassLoader());

    for (final Class<?> type : new Class<?>[] {SessionList.class, WorkflowVersions.class}) {
      assertThat(
              RuntimeHintsPredicates.reflection()
                  .onType(type)
                  .withMemberCategories(
                      MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS))
          .accepts(hints);
    }
  }
}
