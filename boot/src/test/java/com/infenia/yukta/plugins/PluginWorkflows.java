// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * Defines small workflows in a fresh session and runs them through the real application. Results
 * are what reaches the terminal nodes.
 */
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.CommentDefaultAccessModifier",
  "PMD.ShortVariable",
  "PMD.UseArraysAsList"
})
final class PluginWorkflows {

  static final Duration WAIT = Duration.ofSeconds(30);

  private final WorkflowDefinitionStore definitions;
  private final SubWorkflowGateway runner;
  private final PluginRegistry registry;

  /** Skips the calling test when the build was made without this (optional) plugin. */
  void requirePlugin(final String type) {
    assumeThat(registry.contains(type)).as("plugin %s is part of this build", type).isTrue();
  }

  static Node node(final String id, final String type, final Map<String, Object> config) {
    return new Node(id, type, config);
  }

  /** Saves {@code API_TRIGGER -> nodes (in a chain) -> CONSOLE_TERMINAL}, returns its key. */
  Key chain(final Node... nodes) {
    return chainIn("it-" + UUID.randomUUID(), nodes);
  }

  /** Like {@link #chain} in a given session, so workflows can refer to each other. */
  Key chainIn(final String session, final Node... nodes) {
    final List<Node> all = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    all.add(node("in", "API_TRIGGER", Map.of()));
    String previous = "in";
    for (final Node n : nodes) {
      all.add(n);
      edges.add(new Edge(previous, n.nodeId(), null));
      previous = n.nodeId();
    }
    all.add(node("end", "CONSOLE_TERMINAL", Map.of()));
    edges.add(new Edge(previous, "end", null));
    return saveIn(session, all, edges);
  }

  /** Saves any graph in a fresh session. */
  Key save(final List<Node> nodes, final List<Edge> edges) {
    return saveIn("it-" + UUID.randomUUID(), nodes, edges);
  }

  Key saveIn(final String session, final List<Node> nodes, final List<Edge> edges) {
    final String workflowId = "wf-" + UUID.randomUUID();
    definitions
        .save(session, new WorkflowDefinition(workflowId, "plugin it", nodes, edges))
        .block(WAIT);
    return new Key(session, workflowId);
  }

  /** Runs to completion and returns the payloads that reached the terminal nodes. */
  List<Object> run(final Key key, final Map<String, Object> input) {
    return Objects.requireNonNull(
            runner.run(key.session(), key.workflowId(), input, "plugin-it", 1).block(WAIT))
        .results();
  }

  /** Runs {@link #chain} and returns the first payload as a map. */
  @SuppressWarnings("unchecked")
  Map<String, Object> runOne(final Key key, final Map<String, Object> input) {
    final List<Object> results = run(key, input);
    if (results.isEmpty()) {
      throw new AssertionError("nothing reached the terminal");
    }
    return (Map<String, Object>) results.get(0);
  }

  /** A workflow in a session. */
  record Key(String session, String workflowId) {}
}
