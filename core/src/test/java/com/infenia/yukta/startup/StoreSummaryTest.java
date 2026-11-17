// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

class StoreSummaryTest {

  @Test
  void namesTheStoreOfEachKind_andNoneWhereThereIsNone() {
    final StaticApplicationContext context = new StaticApplicationContext();
    context.registerBean(ExecutionLogStore.class, InMemoryExecutionLogStore::new);
    context.refresh();

    final StoreSummary summary = new StoreSummary(context);

    assertThat(summary.describe())
        .containsEntry("executionLog", "InMemoryExecutionLogStore")
        .containsEntry("checkpoints", "none")
        .containsKeys("sessions", "definitions", "inputs", "supervisors", "agents", "leader");
    assertThatCode(summary::onReady).doesNotThrowAnyException();
  }
}
