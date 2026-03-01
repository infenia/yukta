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
package com.infenia.yukta.service;

import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.model.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
@SuppressWarnings("PMD.TooManyMethods")
public class WorkflowValidator {

  private static final int SCRIPT_LIMIT = 50;
  private static final String TYPE_MAPPER = "MAPPER";
  private final WorkflowRegistry registry;

  /**
   * Validate the structural integrity of a workflow definition.
   *
   * @param def the workflow definition
   * @return a Mono that completes if validation is successful
   */
  public Mono<Void> validate(@NotNull @Valid final WorkflowDefinition def) {
    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> sourceIds =
        def.edges().stream().map(WorkflowDefinition.Edge::source).collect(Collectors.toSet());

    return validateEntryPoints(def, targetIds)
        .then(validateProcessors(def, targetIds, sourceIds))
        .then(validateEndpoints(def, sourceIds))
        .then(validateGuards(def, sourceIds))
        .then(validateBranches(def))
        .then(validateNoCycles(def))
        .then(validateNoOrphans(def))
        .then(validateMapper(def))
        .then(validateFilterPlacement(def))
        .then(validatePluginConfigs(def));
  }

  private Mono<Void> validateEntryPoints(
      final WorkflowDefinition def, final Set<String> targetIds) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final WorkflowPlugin plugin = registry.get(node.type());
              if (plugin == null) {
                return Mono.error(
                    new IllegalArgumentException("Plugin not found for type: " + node.type()));
              }
              final boolean isEntryPoint = !targetIds.contains(node.nodeId());
              final boolean canBeTrigger = plugin instanceof TriggerPlugin;
              final boolean mustBeTrigger = plugin.getCategory() == PluginCategory.TRIGGER;

              if (isEntryPoint && !canBeTrigger) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Node " + node.nodeId() + " is an entry point but not a TRIGGER"));
              }
              if (!isEntryPoint && mustBeTrigger) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Trigger node " + node.nodeId() + " cannot have incoming edges"));
              }
              return Mono.empty();
            })
        .then();
  }

  private Mono<Void> validateProcessors(
      final WorkflowDefinition def, final Set<String> targetIds, final Set<String> sourceIds) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final WorkflowPlugin plugin = registry.get(node.type());
              if (plugin != null
                  && plugin.getCategory() == PluginCategory.PROCESSOR
                  && !(!targetIds.contains(node.nodeId()) && plugin instanceof TriggerPlugin)
                  && (!targetIds.contains(node.nodeId()) || !sourceIds.contains(node.nodeId()))) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Processor node "
                            + node.nodeId()
                            + " must have both incoming and outgoing edges"));
              }
              return Mono.empty();
            })
        .then();
  }

  private Mono<Void> validateBranches(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .filter(node -> "BRANCH".equals(node.type()))
        .flatMap(
            node -> {
              final Map<String, Object> config = node.config();
              @SuppressWarnings("unchecked")
              final Map<String, String> cases = (Map<String, String>) config.get("cases");
              final String defaultPort = (String) config.get("defaultPort");

              final Set<String> definedPorts = new HashSet<>();
              if (cases != null) {
                definedPorts.addAll(cases.values());
              }
              if (defaultPort != null) {
                definedPorts.add(defaultPort);
              }

              final Set<String> actualPorts =
                  def.edges().stream()
                      .filter(e -> e.source().equals(node.nodeId()))
                      .map(WorkflowDefinition.Edge::sourcePort)
                      .collect(Collectors.toSet());

              return Flux.fromIterable(definedPorts)
                  .flatMap(
                      definedPort -> {
                        if (!actualPorts.contains(definedPort)) {
                          return Mono.error(
                              new IllegalArgumentException(
                                  "Branch node "
                                      + node.nodeId()
                                      + " has a case routing to port '"
                                      + definedPort
                                      + "' but no outgoing edge exists for this port"));
                        }
                        return Mono.empty();
                      })
                  .then();
            })
        .then();
  }

  private Mono<Void> validateGuards(final WorkflowDefinition def, final Set<String> sourceIds) {
    final Set<String> standardPorts = Set.of("true", "false", "error");
    return Flux.fromIterable(def.nodes())
        .filter(node -> "GUARD".equals(node.type()))
        .flatMap(
            node -> {
              if (!sourceIds.contains(node.nodeId())) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Guard node " + node.nodeId() + " must have at least one outgoing edge"));
              }
              // Validate ports on outgoing edges
              return Flux.fromIterable(def.edges())
                  .filter(e -> e.source().equals(node.nodeId()))
                  .flatMap(
                      edge -> {
                        if (edge.sourcePort() != null
                            && !standardPorts.contains(edge.sourcePort())) {
                          // Allow custom error port if specified in config
                          final String errorPort = (String) node.config().get("errorPort");
                          if (!edge.sourcePort().equals(errorPort)) {
                            return Mono.error(
                                new IllegalArgumentException(
                                    "Invalid sourcePort '"
                                        + edge.sourcePort()
                                        + "' for Guard node "
                                        + node.nodeId()));
                          }
                        }
                        return Mono.empty();
                      })
                  .then();
            })
        .then();
  }

  private Mono<Void> validateEndpoints(final WorkflowDefinition def, final Set<String> sourceIds) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final WorkflowPlugin plugin = registry.get(node.type());
              if (plugin != null) {
                final boolean isEndpoint = !sourceIds.contains(node.nodeId());
                final boolean isTerminal = plugin.getCategory() == PluginCategory.TERMINAL;

                if (isEndpoint && !isTerminal) {
                  return Mono.error(
                      new IllegalArgumentException(
                          "Node " + node.nodeId() + " is an endpoint but not a TERMINAL"));
                }
                if (!isEndpoint && isTerminal) {
                  return Mono.error(
                      new IllegalArgumentException(
                          "Terminal node " + node.nodeId() + " cannot have outgoing edges"));
                }
              }
              return Mono.empty();
            })
        .then();
  }

  private Mono<Void> validateNoCycles(final WorkflowDefinition def) {
    return hasCycles(def)
        ? Mono.error(new IllegalArgumentException("Workflow DAG contains cycles"))
        : Mono.empty();
  }

  private Mono<Void> validateNoOrphans(final WorkflowDefinition def) {
    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> triggerIds =
        def.nodes().stream()
            .filter(
                node -> {
                  final WorkflowPlugin plugin = registry.get(node.type());
                  return plugin instanceof TriggerPlugin && !targetIds.contains(node.nodeId());
                })
            .map(Node::nodeId)
            .collect(Collectors.toSet());

    final Map<String, List<String>> adj = new ConcurrentHashMap<>();
    def.nodes().forEach(node -> adj.put(node.nodeId(), new ArrayList<>()));
    def.edges().forEach(edge -> adj.get(edge.source()).add(edge.target()));

    final Set<String> reachable = new HashSet<>();
    for (final String triggerId : triggerIds) {
      dfs(triggerId, adj, reachable);
    }

    return Flux.fromIterable(def.nodes())
        .flatMap(
            node ->
                reachable.contains(node.nodeId())
                    ? Mono.empty()
                    : Mono.error(
                        new IllegalArgumentException(
                            "Node " + node.nodeId() + " is not reachable from any trigger")))
        .then();
  }

  private void dfs(
      final String nodeId, final Map<String, List<String>> adj, final Set<String> reachable) {
    if (!reachable.contains(nodeId)) {
      reachable.add(nodeId);
      final List<String> children = adj.get(nodeId);
      if (children != null) {
        for (final String child : children) {
          dfs(child, adj, reachable);
        }
      }
    }
  }

  private Mono<Void> validateMapper(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .filter(node -> "MAPPER".equals(node.type()))
        .doOnNext(
            node -> {
              final Map<String, Object> config = node.config();
              final String mode = (String) config.get("mode");
              final Object mapping = config.get("mapping");

              if ("SCRIPT".equals(mode)
                  && mapping instanceof String script
                  && isSimpleScript(script)
                  && log.isWarnEnabled()) {
                log.warn(
                    "Mapper node {} uses SCRIPT mode for a simple transformation. "
                        + "Consider using PROJECTION mode for better performance.",
                    node.nodeId());
              }
            })
        .then();
  }

  private Mono<Void> validateFilterPlacement(final WorkflowDefinition def) {
    final Map<String, Node> nodeMap =
        def.nodes().stream().collect(Collectors.toMap(Node::nodeId, n -> n));
    final Map<String, List<String>> parents = new ConcurrentHashMap<>();
    def.nodes().forEach(node -> parents.put(node.nodeId(), new ArrayList<>()));
    def.edges().forEach(edge -> parents.get(edge.target()).add(edge.source()));

    return Flux.fromIterable(def.nodes())
        .filter(node -> "FILTER".equals(node.type()))
        .doOnNext(
            filterNode -> {
              final int depth = getMinDepth(filterNode.nodeId(), def);
              if (depth > 1
                  && hasHeavyAncestor(filterNode.nodeId(), parents, nodeMap)
                  && log.isWarnEnabled()) {
                log.warn(
                    "Performance Hint: Filter [{}] is positioned after heavy computation. "
                        + "Moving it closer to the Trigger may reduce unnecessary load.",
                    filterNode.nodeId());
              }
            })
        .then();
  }

  private int getMinDepth(final String nodeId, final WorkflowDefinition def) {
    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> triggers =
        def.nodes().stream()
            .filter(
                node -> {
                  final WorkflowPlugin plugin = registry.get(node.type());
                  return plugin instanceof TriggerPlugin && !targetIds.contains(node.nodeId());
                })
            .map(Node::nodeId)
            .collect(Collectors.toSet());

    final Map<String, Integer> dist = new ConcurrentHashMap<>();
    final java.util.Queue<String> queue = new java.util.LinkedList<>();

    for (final String triggerId : triggers) {
      dist.put(triggerId, 0);
      queue.add(triggerId);
    }

    final Map<String, List<String>> adj = new ConcurrentHashMap<>();
    def.nodes().forEach(node -> adj.put(node.nodeId(), new ArrayList<>()));
    def.edges().forEach(edge -> adj.get(edge.source()).add(edge.target()));

    int minDepth = -1;
    while (!queue.isEmpty()) {
      final String current = queue.poll();
      if (current.equals(nodeId)) {
        minDepth = dist.get(current);
        break;
      }
      for (final String neighbor : adj.get(current)) {
        if (!dist.containsKey(neighbor)) {
          dist.put(neighbor, dist.get(current) + 1);
          queue.add(neighbor);
        }
      }
    }
    return minDepth;
  }

  private boolean hasHeavyAncestor(
      final String nodeId,
      final Map<String, List<String>> parents,
      final Map<String, Node> nodeMap) {
    final java.util.Queue<String> queue = new java.util.LinkedList<>(parents.get(nodeId));
    final Set<String> visited = new HashSet<>(parents.get(nodeId));

    boolean heavyAncestor = false;
    while (!queue.isEmpty()) {
      final String current = queue.poll();
      final Node node = nodeMap.get(current);
      if (node != null && isHeavyNode(node)) {
        heavyAncestor = true;
        break;
      }
      for (final String parent : parents.get(current)) {
        if (!visited.contains(parent)) {
          visited.add(parent);
          queue.add(parent);
        }
      }
    }
    return heavyAncestor;
  }

  private boolean isHeavyNode(final Node node) {
    boolean heavy = false;
    if (TYPE_MAPPER.equals(node.type())) {
      heavy = "SCRIPT".equals(node.config().get("mode"));
    }
    return heavy;
  }

  private boolean isSimpleScript(final String script) {
    boolean simple = false;
    if (script.length() < SCRIPT_LIMIT) {
      final String low = script.toLowerCase(Locale.ROOT);
      simple =
          !low.contains("if")
              && !low.contains("for")
              && !low.contains("function")
              && !low.contains("while");
    }
    return simple;
  }

  private Mono<Void> validatePluginConfigs(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final WorkflowPlugin plugin = registry.get(node.type());
              return plugin.validateConfig(node.config());
            })
        .then();
  }

  private boolean hasCycles(final WorkflowDefinition def) {
    final Map<String, List<String>> adj = new ConcurrentHashMap<>();
    def.nodes().forEach(node -> adj.put(node.nodeId(), new ArrayList<>()));
    def.edges().forEach(edge -> adj.get(edge.source()).add(edge.target()));

    final Set<String> visited = new HashSet<>();
    final Set<String> recStack = new HashSet<>();

    boolean result = false;
    for (final String nodeId : adj.keySet()) {
      if (isCyclicUtil(nodeId, visited, recStack, adj)) {
        result = true;
        break;
      }
    }
    return result;
  }

  private boolean isCyclicUtil(
      final String nodeId,
      final Set<String> visited,
      final Set<String> recStack,
      final Map<String, List<String>> adj) {
    boolean found = false;
    if (recStack.contains(nodeId)) {
      found = true;
    } else if (!visited.contains(nodeId)) {
      visited.add(nodeId);
      recStack.add(nodeId);

      found = checkChildrenForCycles(adj.get(nodeId), visited, recStack, adj);

      recStack.remove(nodeId);
    }
    return found;
  }

  private boolean checkChildrenForCycles(
      final List<String> children,
      final Set<String> visited,
      final Set<String> recStack,
      final Map<String, List<String>> adj) {
    boolean found = false;
    if (children != null) {
      for (final String child : children) {
        if (isCyclicUtil(child, visited, recStack, adj)) {
          found = true;
          break;
        }
      }
    }
    return found;
  }
}
