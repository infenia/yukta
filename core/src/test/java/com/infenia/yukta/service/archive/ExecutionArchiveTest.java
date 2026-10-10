// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class ExecutionArchiveTest {

  private final PreparedWorkflow prepared = mock(PreparedWorkflow.class);

  private ArchivedExecution execution(final String executionId) {
    return new ArchivedExecution(executionId, "s", "w", prepared, "v", Map.of());
  }

  @Test
  void keepsTheMostRecentExecutions_andReportsTheEvictedOnes() {
    final ExecutionArchive archive = new ExecutionArchive(2);

    assertThat(archive.put(execution("e1"))).isEmpty();
    assertThat(archive.put(execution("e2"))).isEmpty();
    assertThat(archive.put(execution("e3"))).containsExactly("e1");

    assertThat(archive.find("e1")).isEmpty();
    assertThat(archive.find("e2")).isPresent();
    assertThat(archive.find("e3")).isPresent();
  }

  @Test
  void puttingTheSameExecutionAgain_replacesItWithoutEvicting() {
    final ExecutionArchive archive = new ExecutionArchive(2);
    archive.put(execution("e1"));
    archive.put(execution("e2"));

    assertThat(archive.put(execution("e2"))).isEmpty();
    assertThat(archive.find("e1")).isPresent();
  }

  @Test
  void defaultArchive_keepsTen_andNeverLessThanOne() {
    final ExecutionArchive archive = new ExecutionArchive();
    for (int i = 0; i < 11; i++) {
      archive.put(execution("e" + i));
    }
    assertThat(archive.find("e0")).isEmpty();
    assertThat(archive.find("e1")).isPresent();

    final ExecutionArchive tiny = new ExecutionArchive(0);
    tiny.put(execution("a"));
    tiny.put(execution("b"));
    assertThat(tiny.find("b")).isPresent();
    assertThat(tiny.find("a")).isEmpty();
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void archivedPayload_acceptsNullValuesAndIsIndependentOfTheCaller() {
    final Map<String, Object> payload = new HashMap<>();
    payload.put("a", null);

    final ArchivedExecution archived = new ArchivedExecution("e", "s", "w", prepared, "v", payload);
    payload.put("later", 1);

    assertThat(archived.payload()).containsOnlyKeys("a");
  }
}
