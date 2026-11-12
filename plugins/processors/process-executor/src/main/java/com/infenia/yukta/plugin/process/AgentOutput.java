// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a harness' output line by line as it arrives: shows the worthwhile lines in the run log and
 * keeps the final result line. One instance per run.
 */
final class AgentOutput implements PluginLogSink {

  private final AgentPreset.Output rules;
  private final PluginLogSink downstream;
  private final ObjectMapper json;

  /** The result line, once seen. */
  private volatile @Nullable JsonNode result;

  /**
   * Creates the reader.
   *
   * @param rules how to read this harness' output
   * @param downstream the run log
   * @param json the JSON mapper
   */
  AgentOutput(
      final AgentPreset.Output rules, final PluginLogSink downstream, final ObjectMapper json) {
    this.rules = rules;
    this.downstream = downstream;
    this.json = json;
  }

  @Override
  public void line(final String stream, final String line, final @Nullable LogLevel level) {
    final JsonNode node = parse(line);
    if (node == null) {
      downstream.line(stream, line, level);
      return;
    }
    if (matches(node, rules.resultWhen())) {
      result = node;
      return;
    }
    for (final AgentPreset.Live live : rules.live()) {
      if (matches(node, live.when())) {
        values(node, live.text()).forEach(v -> downstream.line("agent", live.prefix() + v, level));
      }
    }
  }

  /**
   * What the run came to.
   *
   * @param exitCode the process exit code
   * @param timedOut whether the process was killed at its timeout
   * @param timeoutSeconds the timeout, for the message
   * @return the outcome
   */
  Map<String, Object> outcome(
      final int exitCode, final boolean timedOut, final long timeoutSeconds) {
    final JsonNode r = result;
    final Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("turn", 1);
    out.put("exitCode", exitCode);
    if (timedOut) {
      return fail(out, "timed out after " + timeoutSeconds + "s", "");
    }
    if (r == null) {
      return fail(out, "the harness gave no result (exit code " + exitCode + ")", "");
    }
    final String text = first(r, rules.text());
    final String session = first(r, rules.sessionId());
    out.put("sessionId", session);
    final String kind = first(r, rules.subtype());
    final boolean error = "true".equals(first(r, rules.isError()));
    if (rules.incompleteSubtypes().contains(kind)) {
      out.put("status", "incomplete");
      out.put("reason", kind);
      out.put("text", text);
    } else if (error) {
      out.put("status", "failure");
      out.put("reason", text.isBlank() ? kind : text);
      out.put("text", text);
    } else {
      out.put("status", "success");
      out.put("text", text);
    }
    return out;
  }

  private static Map<String, Object> fail(
      final Map<String, Object> out, final String reason, final String text) {
    out.put("status", "failure");
    out.put("reason", reason);
    out.put("text", text);
    return out;
  }

  private @Nullable JsonNode parse(final String line) {
    if (!line.startsWith("{")) {
      return null;
    }
    try {
      return json.readTree(line);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static boolean matches(final JsonNode node, final Map<String, String> when) {
    return !when.isEmpty()
        && when.entrySet().stream()
            .allMatch(e -> e.getValue().equals(node.path(e.getKey()).asString("")));
  }

  private static String first(final JsonNode node, final String path) {
    final List<String> found = values(node, path);
    return found.isEmpty() ? "" : found.getFirst();
  }

  /** Values at a dotted path; a segment {@code name[*]} visits every element of that array. */
  static List<String> values(final JsonNode root, final String path) {
    List<JsonNode> current = List.of(root);
    for (final String segment : path.split("\\.")) {
      final boolean each = segment.endsWith("[*]");
      final String name = each ? segment.substring(0, segment.length() - 3) : segment;
      final List<JsonNode> next = new ArrayList<>();
      for (final JsonNode n : current) {
        final JsonNode child = n.path(name);
        if (each) {
          child.forEach(next::add);
        } else if (!child.isMissingNode() && !child.isNull()) {
          next.add(child);
        }
      }
      current = next;
    }
    return current.stream().map(n -> n.asString("")).filter(s -> !s.isEmpty()).toList();
  }
}
