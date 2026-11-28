// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.testing.TestReport;
import com.infenia.yukta.service.testing.WorkflowTestRunner;
import com.infenia.yukta.service.testing.WorkflowTestSpec;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
class WorkflowTestToolsTest {

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final WorkflowTestRunner runner = mock(WorkflowTestRunner.class);
  private final WorkflowTestTools tools =
      new WorkflowTestTools(definitions, runner, new ObjectMapper());

  @Test
  void testsTheSavedWorkflow_withTheArgumentsAsTheSpec() {
    final WorkflowDefinition definition =
        new WorkflowDefinition("w", "d", List.of(), List.of(), List.of(), Map.of());
    final TestReport report =
        new TestReport(true, "SUCCESS", List.of(new TestReport.Check("status", true, null)));
    when(definitions.find("s", "w")).thenReturn(Mono.just(definition));
    when(runner.run(eq("s"), eq(definition), any())).thenReturn(Mono.just(report));

    StepVerifier.create(
            tools.runWorkflowTest(
                "s",
                "w",
                Map.of("env", "prod"),
                Map.of("build", List.of(Map.of("payload", Map.of("exitCode", 0), "port", "ok"))),
                Map.of("status", "SUCCESS", "reached", List.of("notify")),
                List.of("map"),
                30))
        .expectNext(report)
        .verifyComplete();

    final ArgumentCaptor<WorkflowTestSpec> spec = ArgumentCaptor.forClass(WorkflowTestSpec.class);
    verify(runner).run(eq("s"), eq(definition), spec.capture());
    assertThat(spec.getValue().input()).containsEntry("env", "prod");
    assertThat(spec.getValue().stubs().get("build").getFirst().port()).isEqualTo("ok");
    assertThat(spec.getValue().expect().reached()).containsExactly("notify");
    assertThat(spec.getValue().allow()).containsExactly("map");
    assertThat(spec.getValue().timeoutSeconds()).isEqualTo(30);
  }

  @Test
  void unknownWorkflow_isAnError() {
    when(definitions.find("s", "gone")).thenReturn(Mono.empty());

    StepVerifier.create(tools.runWorkflowTest("s", "gone", null, null, null, null, null))
        .expectError(NotFoundException.class)
        .verify();
  }
}
