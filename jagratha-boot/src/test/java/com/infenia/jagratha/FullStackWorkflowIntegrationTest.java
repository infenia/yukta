/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.jagratha.harness.WorkflowTestHarness;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowDefinition.Edge;
import com.infenia.jagratha.model.WorkflowDefinition.Node;
import com.infenia.jagratha.model.WorkflowProgress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class FullStackWorkflowIntegrationTest {

  @Autowired private WebTestClient webClient;

  private WorkflowTestHarness harness;

  @BeforeEach
  void setUp() {
    harness = new WorkflowTestHarness(webClient);
  }

  @Test
  void testComplexWorkflow_AllCorePlugins() {
    final String sessionId = "test-session-" + UUID.randomUUID();

    // Define a complex workflow
    final Node trigger = new Node("trigger", "api-trigger", Map.of());

    // Mapper to add a field
    final Node mapper =
        new Node(
            "mapper",
            "MAPPER",
            Map.of("mode", "PROJECTION", "mapping", Map.of("value", "payload.input * 2")));

    // Filter to check value
    final Node filter = new Node("filter", "FILTER", Map.of("condition", "payload.value > 10"));

    // Branch to route
    final Node branch =
        new Node(
            "branch",
            "BRANCH",
            Map.of(
                "mode", "SELECT_KEY",
                "selector", "payload.value > 20 ? 'high' : 'low'",
                "cases",
                    Map.of(
                        "high", "highPort",
                        "low", "lowPort")));

    // Aggregator to collect results
    final Node aggregator =
        new Node(
            "aggregator",
            "AGGREGATOR",
            Map.of(
                "groupBy", "'static'",
                "window", Map.of("type", "COUNT", "size", 1),
                "aggregation", Map.of("type", "SUM", "field", "payload.value")));

    // Terminal
    final Node terminal = new Node("terminal", "console", Map.of());

    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "Complex Workflow",
            List.of(trigger, mapper, filter, branch, aggregator, terminal),
            List.of(
                new Edge("trigger", "mapper"),
                new Edge("mapper", "filter"),
                new Edge("filter", "branch"),
                new Edge("branch", "aggregator", "highPort"),
                new Edge("branch", "aggregator", "lowPort"),
                new Edge("aggregator", "terminal")));

    final ConfigRequest configRequest =
        new ConfigRequest(
            sessionId,
            "Integration Test Workflow",
            "TestRunner",
            Map.of("env", "test"),
            System.getProperty("java.io.tmpdir"),
            Map.of("main-flow", workflow));

    // 1. Initialize
    harness.initSession(configRequest);

    // 2. Trigger
    harness.triggerWorkflow(sessionId, "main-flow", Map.of("input", 15));

    // 3. Poll and Verify
    final WorkflowProgress progress = harness.pollUntilFinished(sessionId, "main-flow");
    harness.verifyStatus(progress, "SUCCESS");

    // Verify task sequence
    assertThat(progress.tasks())
        .extracting("nodeId")
        .containsSubsequence("trigger", "mapper", "filter", "branch", "aggregator", "terminal");

    // Verify performance header was checked in harness (it throws if not present)
  }

  @Test
  void testLoopPlugin() {
    final String sessionId = "loop-session-" + UUID.randomUUID();

    final Node trigger = new Node("trigger", "api-trigger", Map.of());

    final Node loop =
        new Node(
            "loop",
            "LOOP_STREAM",
            Map.of(
                "targetPluginId",
                "MAPPER", // Type MAPPER in registry
                "targetConfig",
                Map.of(
                    "mode", "PROJECTION", "mapping", Map.of("count", "(payload.count ?: 0) + 1")),
                "exitCondition",
                "payload.count >= 5",
                "maxIterations",
                10));

    final Node terminal = new Node("terminal", "console", Map.of());

    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "Loop Workflow",
            List.of(trigger, loop, terminal),
            List.of(new Edge("trigger", "loop"), new Edge("loop", "terminal")));

    final ConfigRequest configRequest =
        new ConfigRequest(
            sessionId,
            "Loop Test",
            "TestRunner",
            Map.of(),
            System.getProperty("java.io.tmpdir"),
            Map.of("loop-flow", workflow));

    harness.initSession(configRequest);
    harness.triggerWorkflow(sessionId, "loop-flow", Map.of("count", 0));

    final WorkflowProgress progress = harness.pollUntilFinished(sessionId, "loop-flow");
    harness.verifyStatus(progress, "SUCCESS");
  }

  @Test
  void testGuardJoinSubWorkflow() {
    final String sessionId = "adv-session-" + UUID.randomUUID();

    // 1. Trigger
    final Node trigger = new Node("trigger", "api-trigger", Map.of());

    // 2. Guard
    final Node guard = new Node("guard", "GUARD", Map.of("condition", "payload.amount > 100"));

    // 3. Branches
    final Node mapperTrue =
        new Node(
            "mapperTrue",
            "MAPPER",
            Map.of("mode", "PROJECTION", "mapping", Map.of("processed", "true")));
    final Node mapperFalse =
        new Node(
            "mapperFalse",
            "MAPPER",
            Map.of("mode", "PROJECTION", "mapping", Map.of("processed", "false")));

    // 4. Join
    final Node join = new Node("join", "JOIN", Map.of("mode", "ANY"));

    // 5. Sub-workflow Node
    final Node subWorkflowNode =
        new Node("sub", "SUB_WORKFLOW", Map.of("subWorkflowId", "child-flow"));

    // 6. Terminal
    final Node terminal = new Node("terminal", "console", Map.of());

    // Main workflow
    final WorkflowDefinition mainWorkflow =
        new WorkflowDefinition(
            "Main Flow",
            List.of(trigger, guard, mapperTrue, mapperFalse, join, subWorkflowNode, terminal),
            List.of(
                new Edge("trigger", "guard"),
                new Edge("guard", "mapperTrue", "true"),
                new Edge("guard", "mapperFalse", "false"),
                new Edge("mapperTrue", "join"),
                new Edge("mapperFalse", "join"),
                new Edge("join", "sub"),
                new Edge("sub", "terminal")));

    // Child workflow
    final WorkflowDefinition childWorkflow =
        new WorkflowDefinition(
            "Child Flow",
            List.of(
                new Node("t1", "api-trigger", Map.of()),
                new Node(
                    "m1",
                    "MAPPER",
                    Map.of("mode", "PROJECTION", "mapping", Map.of("subResult", "'done'"))),
                new Node("term", "console", Map.of())),
            List.of(new Edge("t1", "m1"), new Edge("m1", "term")));

    final ConfigRequest configRequest =
        new ConfigRequest(
            sessionId,
            "Advanced Test",
            "TestRunner",
            Map.of(),
            System.getProperty("java.io.tmpdir"),
            Map.of(
                "main-flow", mainWorkflow,
                "child-flow", childWorkflow));

    harness.initSession(configRequest);
    harness.triggerWorkflow(sessionId, "main-flow", Map.of("amount", 150));

    final WorkflowProgress progress = harness.pollUntilFinished(sessionId, "main-flow");
    harness.verifyStatus(progress, "SUCCESS");

    assertThat(progress.tasks())
        .extracting("nodeId")
        .contains("trigger", "guard", "mapperTrue", "join", "sub", "terminal", "mapperFalse");

    final var mapperFalseTask =
        progress.tasks().stream()
            .filter(t -> "mapperFalse".equals(t.nodeId()))
            .findFirst()
            .orElseThrow();
    assertThat(mapperFalseTask.status()).isEqualTo("SKIPPED");

    final var mapperTrueTask =
        progress.tasks().stream()
            .filter(t -> "mapperTrue".equals(t.nodeId()))
            .findFirst()
            .orElseThrow();
    assertThat(mapperTrueTask.status()).isEqualTo("SUCCESS");
  }
}
