// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.response.WorkflowValidationResult;
import com.infenia.yukta.mapper.AppConfigMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.orchestrator.validator.WorkflowValidator;
import com.infenia.yukta.service.plugin.PluginRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Dry-run validation of a draft workflow, for editors that check before saving. */
@Validated
@RestController
@RequestMapping("/api/sessions/validate")
@RequiredArgsConstructor
@Tag(name = "Session API", description = "Validate a draft workflow without saving it")
public class WorkflowValidationController {

  /** Maps draft workflow requests to the domain model. */
  private final AppConfigMapper appConfigMapper;

  /** Resolves node types to plugins, for port lookup. */
  private final PluginRegistry pluginRegistry;

  /** The same validator execution uses. */
  private final WorkflowValidator workflowValidator;

  /**
   * Validate a draft workflow.
   *
   * @param request the draft workflow
   * @return whether it would be accepted, the first problem, and each node's output ports
   */
  @PostMapping
  @Operation(
      summary = "Validate a workflow",
      description =
          "Checks a draft workflow with the same rules as execution, without saving it. A"
              + " failing draft is a 200 with valid=false. Also returns each node's output ports"
              + " for its configuration, so an editor can offer the right edge ports.")
  public Mono<ApiResponse<WorkflowValidationResult>> validateWorkflow(
      @Valid @RequestBody final WorkflowDefinitionRequest request) {
    final WorkflowDefinition definition = appConfigMapper.toWorkflowDefinition(request);
    final Map<String, List<String>> ports =
        definition.nodes().stream()
            .filter(node -> pluginRegistry.get(node.type()) != null)
            .collect(
                Collectors.toMap(
                    WorkflowDefinition.Node::nodeId,
                    node -> portsOf(pluginRegistry.get(node.type()), node),
                    // A duplicate node id is for the validator to report, not a 500 here.
                    (first, second) -> first));
    return workflowValidator
        .validate(definition)
        .thenReturn(new WorkflowValidationResult(true, null, ports))
        .onErrorResume(
            e ->
                Mono.just(
                    new WorkflowValidationResult(
                        false, e.getMessage() != null ? e.getMessage() : e.toString(), ports)))
        .map(result -> ApiResponse.success(200, "Workflow validated", result));
  }

  /** The node's output ports, or none when its draft configuration is too incomplete to say. */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static List<String> portsOf(final Plugin plugin, final WorkflowDefinition.Node node) {
    List<String> ports;
    try {
      ports = plugin.getOutputPorts(Objects.requireNonNull(node.config()));
    } catch (RuntimeException e) {
      ports = List.of();
    }
    return ports;
  }
}
