// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.service.testing.TestReport;
import com.infenia.yukta.service.testing.WorkflowTestRunner;
import com.infenia.yukta.service.testing.WorkflowTestSpec;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.aot.DisabledInAotMode;

/** A workflow test through the real engine: stubs replace the process, assertions read the run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@NoArgsConstructor
@DisabledInAotMode
class WorkflowTestRunnerAcceptanceTest {

  @Autowired private WorkflowTestRunner runner;

  @Test
  void stubbedProcessor_replacesTheRealOne_andTheAssertionsAreChecked() {
    final var def =
        Fixtures.workflow(
            "wf",
            "d",
            List.of(
                Fixtures.node("t", "CONSTANT_SOURCE", Map.of()),
                Fixtures.node("p", "PROCESS_EXECUTOR", Map.of("command", List.of("false"))),
                Fixtures.node("end", "CONSOLE_TERMINAL", Map.of())),
            List.of(new Edge("t", "p", null), new Edge("p", "end", null)));
    final var spec =
        new WorkflowTestSpec(
            Map.of("env", "prod"),
            Map.of("p", List.of(new WorkflowTestSpec.Stub(Map.of("status", "ok"), null))),
            new WorkflowTestSpec.Expect(
                "SUCCESS",
                List.of("p", "end"),
                List.of(),
                Map.of("end", Map.of("payload.status", "ok", "payload.other", "x"))),
            null,
            30);

    final TestReport report = runner.run("test-session", def, spec).block();

    assertThat(report.status()).isEqualTo("SUCCESS");
    assertThat(report.assertions())
        .extracting(TestReport.Check::name, TestReport.Check::passed)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("status is SUCCESS", true),
            org.assertj.core.api.Assertions.tuple("reached p", true),
            org.assertj.core.api.Assertions.tuple("reached end", true),
            org.assertj.core.api.Assertions.tuple("end payload.status = ok", true),
            org.assertj.core.api.Assertions.tuple("end payload.other = x", false));
    assertThat(report.passed()).isFalse();
  }
}
