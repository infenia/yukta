// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.testing.TestReport;
import com.infenia.yukta.service.testing.WorkflowTestRunner;
import com.infenia.yukta.service.testing.WorkflowTestSpec;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** {@link WorkflowTestController}. */
class WorkflowTestControllerTest {

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final WorkflowTestRunner runner = mock(WorkflowTestRunner.class);
  private final WorkflowTestController controller = new WorkflowTestController(definitions, runner);
  private final WorkflowTestSpec spec = new WorkflowTestSpec(null, null, null, null, null);

  @Test
  void runsTheSavedWorkflowAndReturnsTheReport() {
    final var def =
        Fixtures.workflow(
            "wf", "d", List.of(Fixtures.node("a", "T", java.util.Map.of())), List.of());
    final var report = new TestReport(true, "SUCCESS", List.of());
    when(definitions.find("s", "wf")).thenReturn(Mono.just(def));
    when(runner.run("s", def, spec)).thenReturn(Mono.just(report));

    assertThat(controller.runTest("s", "wf", spec).block().data()).isEqualTo(report);
  }

  @Test
  void unknownWorkflowIsNotFound() {
    when(definitions.find("s", "nope")).thenReturn(Mono.empty());

    assertThatThrownBy(() -> controller.runTest("s", "nope", spec).block())
        .isInstanceOf(NotFoundException.class);
  }
}
