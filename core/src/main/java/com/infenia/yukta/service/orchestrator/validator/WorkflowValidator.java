// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.plugin.PluginRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Validates workflow structural integrity and plugin configurations. The checks live in {@link
 * SecretRules}, {@link StructuralRules}, {@link PolicyRules}, {@link GraphRules} and {@link
 * PluginChecks}; this class runs them in order.
 */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class WorkflowValidator {

  /** The plugin registry for resolving plugin types. */
  private final PluginRegistry registry;

  /**
   * Validate the structural integrity of a workflow definition.
   *
   * @param def the workflow definition
   * @return a Mono that completes if validation is successful
   */
  public Mono<Void> validate(@NotNull @Valid final WorkflowDefinition def) {
    log.atDebug()
        .addKeyValue("workflowId", def.workflowId())
        .addKeyValue("nodeCount", def.nodes().size())
        .addKeyValue("edgeCount", def.edges().size())
        .log("Starting workflow validation");

    final List<String> eager = findEagerProblems(def);
    if (!eager.isEmpty()) {
      return Mono.error(new IllegalArgumentException(eager.getFirst()));
    }
    final List<String> graph = GraphRules.findProblems(def, registry);
    final Mono<Void> checks =
        graph.isEmpty()
            ? Flux.concat(
                    PluginChecks.validateNodeContexts(def, registry),
                    PluginChecks.validatePluginConfigs(def, registry))
                .then()
            : Mono.error(new IllegalArgumentException(graph.getFirst()));
    return checks
        .doOnSuccess(
            _ ->
                log.atInfo()
                    .addKeyValue("workflowId", def.workflowId())
                    .log("Workflow validation completed successfully"))
        .doOnError(
            e ->
                log.atError()
                    .setCause(e)
                    .addKeyValue("workflowId", def.workflowId())
                    .log("Workflow validation failed"));
  }

  /** A secret mask, a literal secret, a bad policy or a broken structure: all of them. */
  private List<String> findEagerProblems(final WorkflowDefinition def) {
    final List<String> problems = new ArrayList<>(SecretRules.findProblems(def, registry));
    PolicyRules.findInvalidPolicy(def, registry).ifPresent(problems::add);
    StructuralRules.findStructuralProblem(def, registry).ifPresent(problems::add);
    return problems;
  }

  /**
   * Validates like {@link #validate} but reports every problem, not only the first, so an editor or
   * an agent fixes them in one round trip. If the structure itself is broken (duplicate ids, edges
   * to unknown nodes) the checks that assume it are skipped.
   *
   * @param def the workflow
   * @return the problems, empty if the workflow is valid
   */
  public Mono<List<String>> validateAll(@NotNull @Valid final WorkflowDefinition def) {
    final List<String> problems = findEagerProblems(def);
    if (StructuralRules.findStructuralProblem(def, registry).isPresent()) {
      return Mono.just(problems);
    }
    problems.addAll(GraphRules.findProblems(def, registry));
    if (!GraphRules.allRegistered(def, registry)) {
      return Mono.just(problems);
    }
    return Flux.just(
            PluginChecks.validateNodeContexts(def, registry),
            PluginChecks.validatePluginConfigs(def, registry))
        .concatMap(
            check ->
                check.onErrorResume(
                    IllegalArgumentException.class,
                    e -> {
                      problems.add(e.getMessage() != null ? e.getMessage() : e.toString());
                      return Mono.empty();
                    }))
        .then(Mono.fromSupplier(() -> List.copyOf(problems)));
  }
}
