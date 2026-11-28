// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.testing.TestReport;
import com.infenia.yukta.service.testing.WorkflowTestRunner;
import com.infenia.yukta.service.testing.WorkflowTestSpec;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Runs a saved workflow with stubbed nodes and assertions, the MCP form of the test endpoint. */
@Component
@RequiredArgsConstructor
public class WorkflowTestTools {

  /** The saved workflows. */
  private final WorkflowDefinitionStore definitions;

  /** Runs a test. */
  private final WorkflowTestRunner runner;

  /** Turns the tool's arguments into a test spec. */
  private final ObjectMapper mapper;

  /**
   * Tests a workflow.
   *
   * @param sessionId the session
   * @param workflowId the saved workflow
   * @param input what the trigger emits
   * @param stubs per node id, the messages it emits instead of running
   * @param expect the assertions
   * @param allow nodes without a stub that may run for real
   * @param timeoutSeconds longest the test may run
   * @return the report: passed, the run's status and each assertion
   */
  @McpTool(
      name = "test_workflow",
      title = "Test Workflow",
      description =
          "Run a saved workflow once with stubbed nodes and check assertions, without its side"
              + " effects: triggers emit `input` (or their stub), terminals do nothing, and a"
              + " processor with a stub emits it instead of running. Any other processor runs for"
              + " real, so stub every node with a side effect (process, HTTP, model, SQL) or list"
              + " the ones that may run in `allow`. `stubs` maps a node id to a list of"
              + " {payload, port}; `expect` takes status (SUCCESS, FAILURE...), reached and"
              + " notReached (node ids) and nodes: {terminalNodeId: {\"payload.path\": value}}."
              + " Returns passed and one line per assertion; a failing assertion is a result,"
              + " not an error.",
      generateOutputSchema = true,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = false,
              openWorldHint = false))
  public Mono<TestReport> runWorkflowTest(
      @McpToolParam(required = true, description = "The session of the workflow")
          final String sessionId,
      @McpToolParam(required = true, description = "The saved workflow to test")
          final String workflowId,
      @McpToolParam(required = false, description = "What the trigger emits")
          final Map<String, Object> input,
      @McpToolParam(
              required = false,
              description = "Per node id, a list of {payload, port} it emits instead of running")
          final Map<String, Object> stubs,
      @McpToolParam(
              required = false,
              description = "Assertions: status, reached, notReached, nodes")
          final Map<String, Object> expect,
      @McpToolParam(required = false, description = "Nodes without a stub that may run for real")
          final List<String> allow,
      @McpToolParam(required = false, description = "Longest the test may run, in seconds")
          final Integer timeoutSeconds) {
    final Spec spec = new Spec(input, stubs, expect, allow, timeoutSeconds);
    return Mono.fromCallable(() -> mapper.convertValue(spec, WorkflowTestSpec.class))
        .flatMap(test ->
            definitions
                .find(sessionId, workflowId)
                .switchIfEmpty(
                    Mono.error(new NotFoundException("Workflow not found: " + workflowId)))
                .flatMap(definition -> runner.run(sessionId, definition, test)));
  }

  /** The tool's arguments, in the shape of a test spec. */
  private record Spec(
      Map<String, Object> input,
      Map<String, Object> stubs,
      Map<String, Object> expect,
      List<String> allow,
      Integer timeoutSeconds) {}
}
