// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.session.InternalSessions;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the catalog entry and the settings declared by {@link WorkflowSupervisorFactory}. */
@NoArgsConstructor
class WorkflowSupervisorFactoryTest {

  private final WorkflowSupervisorFactory factory =
      new WorkflowSupervisorFactory(
          mock(SupervisorWorkflowRunner.class),
          mock(InternalSessions.class),
          mock(InputRedactor.class));

  @Test
  void describesItselfAndDeclaresItsSettings() {
    final SupervisorDescriptor described = factory.describe();

    assertThat(factory.type()).isEqualTo("workflow");
    assertThat(described.displayName()).isEqualTo("Workflow");
    assertThat(described.category()).isEqualTo(SupervisorCategory.WORKFLOW);
    assertThat(described.uiDesign()).isPresent();
    assertThat(described.exampleSettings())
        .containsEntry("sessionId", "supervisors")
        .containsEntry("workflowId", "triage");
    assertThat(factory.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly("sessionId", "workflowId", "events", "when", "maxConcurrent", "timeout");
    assertThat(factory.getConfigSchema())
        .filteredOn(ConfigField::required)
        .extracting(ConfigField::name)
        .containsExactly("sessionId", "workflowId", "events");
  }
}
