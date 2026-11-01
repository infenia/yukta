// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Service for validating workflow structural integrity and plugin configurations. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.TooManyMethods"
})
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
    final List<String> graph = graphProblems(def);
    final Mono<Void> checks =
        graph.isEmpty()
            ? Flux.concat(validateNodeContexts(def), validatePluginConfigs(def)).then()
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
    final List<String> problems = new ArrayList<>();
    final List<String> masked = ConfigRedactor.placeholders(def);
    if (!masked.isEmpty()) {
      problems.add(
          "The definition holds the secret mask '"
              + ConfigRedactor.PLACEHOLDER
              + "' at "
              + String.join(", ", masked)
              + ". The read APIs mask literal secrets; replace each with the real value or a"
              + " decrypted:<key> reference before saving, or the stored secret would be"
              + " lost");
    }
    final List<String> literals = new ConfigRedactor(registry).literalSecrets(def);
    if (!literals.isEmpty()) {
      problems.add(
          "A workflow definition must not hold secrets, but "
              + String.join(", ", literals)
              + " is a literal value. Store the secret as YUKTA_SECRET_<KEY> and write"
              + " decrypted:<key> (or a ${...} variable) here instead");
    }
    StructuralRules.findInvalidPolicy(def, registry).ifPresent(problems::add);
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
    problems.addAll(graphProblems(def));
    if (!allRegistered(def)) {
      return Mono.just(problems);
    }
    return Flux.just(validateNodeContexts(def), validatePluginConfigs(def))
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

  private boolean allRegistered(final WorkflowDefinition def) {
    return def.nodes().stream().allMatch(node -> registry.get(node.type()) != null);
  }

  /**
   * The synchronous checks of the graph, in the order they run, each giving at most its first
   * problem. Every check after the plugin lookup assumes each node has a registered plugin, so when
   * one has none only the lookup is reported.
   */
  private List<String> graphProblems(final WorkflowDefinition def) {
    final List<String> problems = new ArrayList<>();
    if (!allRegistered(def)) {
      def.nodes().stream()
          .filter(node -> registry.get(node.type()) == null)
          .findFirst()
          .ifPresent(node -> problems.add("Plugin not found for type: " + node.type()));
      return problems;
    }
    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> sourceIds =
        def.edges().stream().map(WorkflowDefinition.Edge::source).collect(Collectors.toSet());
    final List<Optional<String>> checks =
        List.of(
            requireNode(def, plugin -> plugin instanceof TriggerPlugin, "TRIGGER"),
            requireNode(def, WorkflowValidator::isTerminal, "TERMINAL"),
            firstProblem(def, node -> entryPoint(node, targetIds)),
            firstProblem(def, node -> processor(node, sourceIds)),
            firstProblem(def, node -> endpoint(node, sourceIds)),
            hasCycles(def)
                ? Optional.of("Workflow DAG contains cycles")
                : Optional.<String>empty());
    checks.forEach(check -> check.ifPresent(problems::add));
    return problems;
  }

  private Optional<String> firstProblem(
      final WorkflowDefinition def,
      final Function<WorkflowDefinition.Node, Optional<String>> check) {
    return def.nodes().stream().map(check).flatMap(Optional::stream).findFirst();
  }

  private Optional<String> requireNode(
      final WorkflowDefinition def, final Predicate<Plugin> wanted, final String category) {
    final boolean found = def.nodes().stream().anyMatch(n -> wanted.test(registry.get(n.type())));
    return found
        ? Optional.empty()
        : Optional.of("Workflow must contain at least one " + category + " node");
  }

  /**
   * A terminal must declare the category and be a {@link TerminalPlugin}, because the assembler
   * only builds a terminal from the type; a plugin that has only one of them is not accepted.
   */
  private static boolean isTerminal(final Plugin plugin) {
    return plugin.getCategory() == PluginCategory.TERMINAL && plugin instanceof TerminalPlugin;
  }

  private Optional<String> entryPoint(
      final WorkflowDefinition.Node node, final Set<String> targets) {
    final Plugin plugin = registry.get(node.type());
    final boolean isEntryPoint = !targets.contains(node.nodeId());
    if (isEntryPoint && !(plugin instanceof TriggerPlugin)) {
      return Optional.of("Node " + node.nodeId() + " is an entry point but not a TRIGGER");
    }
    if (!isEntryPoint && plugin.getCategory() == PluginCategory.TRIGGER) {
      return Optional.of("Trigger node " + node.nodeId() + " cannot have incoming edges");
    }
    return Optional.empty();
  }

  /** A processor without incoming edges is already rejected by {@link #entryPoint}. */
  private Optional<String> processor(
      final WorkflowDefinition.Node node, final Set<String> sources) {
    final boolean noOutgoing = !sources.contains(node.nodeId());
    return noOutgoing && registry.get(node.type()).getCategory() == PluginCategory.PROCESSOR
        ? Optional.of(
            "Processor node " + node.nodeId() + " must have both incoming and outgoing edges")
        : Optional.empty();
  }

  private Optional<String> endpoint(final WorkflowDefinition.Node node, final Set<String> sources) {
    final boolean isEndpoint = !sources.contains(node.nodeId());
    final boolean isTerminal = isTerminal(registry.get(node.type()));
    if (isEndpoint && !isTerminal) {
      return Optional.of("Node " + node.nodeId() + " is an endpoint but not a TERMINAL");
    }
    if (!isEndpoint && isTerminal) {
      return Optional.of("Terminal node " + node.nodeId() + " cannot have outgoing edges");
    }
    return Optional.empty();
  }

  private Mono<Void> validateNodeContexts(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node ->
                registry
                    .get(node.type())
                    .validateInContext(buildContext(node.nodeId(), def), node.config()))
        .then();
  }

  private WorkflowContext buildContext(final String nodeId, final WorkflowDefinition def) {
    final List<WorkflowEdge> outgoing = new ArrayList<>();
    final List<WorkflowEdge> incoming = new ArrayList<>();

    for (final WorkflowDefinition.Edge edge : def.edges()) {
      final var workflowEdge = new WorkflowEdge(edge.source(), edge.target(), edge.sourcePort());
      if (edge.source().equals(nodeId)) {
        outgoing.add(workflowEdge);
      }
      if (edge.target().equals(nodeId)) {
        incoming.add(workflowEdge);
      }
    }

    return new WorkflowContext(nodeId, outgoing, incoming);
  }

  private Mono<Void> validatePluginConfigs(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              log.atDebug()
                  .addKeyValue("nodeId", node.nodeId())
                  .addKeyValue("pluginType", node.type())
                  .log("Validating plugin configuration");
              return plugin
                  .validateConfig(node.config())
                  .doOnError(
                      e ->
                          log.atWarn()
                              .setCause(e)
                              .addKeyValue("nodeId", node.nodeId())
                              .addKeyValue("pluginType", node.type())
                              .log("Plugin configuration validation failed"));
            })
        .then()
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("workflowId", def.workflowId())
                    .log("All plugin configurations validated successfully"));
  }

  /** Kahn's algorithm: the graph has a cycle if not every node can be taken off in order. */
  private boolean hasCycles(final WorkflowDefinition def) {
    final Map<String, Integer> inDegree = new ConcurrentHashMap<>();
    final Map<String, List<String>> adj = new ConcurrentHashMap<>();
    def.nodes()
        .forEach(
            node -> {
              inDegree.put(node.nodeId(), 0);
              adj.put(node.nodeId(), new ArrayList<>());
            });
    def.edges()
        .forEach(
            edge -> {
              adj.get(edge.source()).add(edge.target());
              inDegree.merge(edge.target(), 1, Integer::sum);
            });
    final Deque<String> ready = new ArrayDeque<>();
    inDegree.forEach((nodeId, degree) -> addIfZero(ready, nodeId, degree));
    int removed = 0;
    while (!ready.isEmpty()) {
      removed++;
      for (final String child : adj.get(ready.poll())) {
        addIfZero(ready, child, inDegree.merge(child, -1, Integer::sum));
      }
    }
    return removed != inDegree.size();
  }

  private static void addIfZero(final Deque<String> ready, final String nodeId, final int degree) {
    if (degree == 0) {
      ready.add(nodeId);
    }
  }
}
