// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a harness' output line by line as it arrives: shows the worthwhile lines in the run log and
 * keeps the final result line. One instance per run.
 */
final class AgentOutput implements PluginLogSink {

  private static final String SESSION_ID = "sessionId";
  private static final String TEXT = "text";
  private static final String STATUS = "status";
  private static final String FAILURE = "failure";

  private final AgentPreset.Output rules;
  private final PluginLogSink downstream;
  private final ObjectMapper json;
  private final AgentPreset.@Nullable Interactive talk;

  /** What the captures kept, by target. */
  private final Map<String, String> captured = new java.util.concurrent.ConcurrentHashMap<>();

  /** Handles requests, when the run holds a conversation. */
  private final AtomicReference<@Nullable Consumer<Request>> onRequest = new AtomicReference<>();

  /** Runs when the result line arrives, when the run holds a conversation. */
  private final AtomicReference<@Nullable Runnable> onResult = new AtomicReference<>();

  /** The failure line, once seen. */
  private final AtomicReference<@Nullable JsonNode> failure = new AtomicReference<>();

  /** The result line, once seen. */
  private final AtomicReference<@Nullable JsonNode> result = new AtomicReference<>();

  /** What a harness asks for while it works (for example, to use a tool). */
  /* default */ record Request(String id, String tool, JsonNode input, String description) {}

  /**
   * Creates the reader.
   *
   * @param rules how to read this harness' output
   * @param downstream the run log
   * @param json the JSON mapper
   */
  /* default */ AgentOutput(
      final AgentPreset.Output rules, final PluginLogSink downstream, final ObjectMapper json) {
    this(rules, null, downstream, json);
  }

  /**
   * Creates the reader for a run that holds a conversation.
   *
   * @param rules how to read this harness' output
   * @param talk how to read its requests, or null
   * @param downstream the run log
   * @param json the JSON mapper
   */
  /* default */ AgentOutput(
      final AgentPreset.Output rules,
      final AgentPreset.@Nullable Interactive talk,
      final PluginLogSink downstream,
      final ObjectMapper json) {
    this.rules = rules;
    this.talk = talk;
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
    capture(node);
    if (JsonPaths.matches(node, rules.failureWhen())) {
      failure.set(node);
      notifyResult();
    } else if (JsonPaths.matches(node, rules.resultWhen())) {
      result.set(node);
      notifyResult();
    } else if (!request(node)) {
      show(node, level);
    }
  }

  private void capture(final JsonNode node) {
    for (final AgentPreset.Capture capture : rules.captures()) {
      if (JsonPaths.matches(node, capture.when())) {
        final String value = JsonPaths.first(node, capture.path());
        if (!value.isEmpty()) {
          captured.put(capture.into(), value);
        }
      }
    }
  }

  private void notifyResult() {
    final Runnable done = onResult.get();
    if (done != null) {
      done.run();
    }
  }

  /** Hands the line to the request handler when it is a request; false when it is not one. */
  private boolean request(final JsonNode node) {
    final Consumer<Request> handler = onRequest.get();
    if (talk == null || handler == null || !JsonPaths.matches(node, talk.requestWhen())) {
      return false;
    }
    handler.accept(
        new Request(
            JsonPaths.first(node, talk.requestId()),
            JsonPaths.first(node, talk.tool()),
            JsonPaths.pathNode(node, talk.input()),
            JsonPaths.first(node, talk.description())));
    return true;
  }

  private void show(final JsonNode node, final @Nullable LogLevel level) {
    for (final AgentPreset.Live live : rules.live()) {
      if (JsonPaths.matches(node, live.when())) {
        JsonPaths.values(node, live.text())
            .forEach(v -> downstream.line("agent", live.prefix() + v, level));
      }
    }
  }

  /**
   * Sets what happens on a request and when the result arrives.
   *
   * @param requests handles each request
   * @param finished runs once the result line is seen
   */
  /* default */ void converse(final Consumer<Request> requests, final Runnable finished) {
    this.onRequest.set(requests);
    this.onResult.set(finished);
  }

  /**
   * What the run came to.
   *
   * @param exitCode the process exit code
   * @param timedOut whether the process was killed at its timeout
   * @param timeoutSeconds the timeout, for the message
   * @return the outcome
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  /* default */ Map<String, Object> outcome(
      final int exitCode, final boolean timedOut, final long timeoutSeconds) {
    final Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("turn", 1);
    out.put("exitCode", exitCode);
    if (timedOut) {
      return fail(out, "timed out after " + timeoutSeconds + "s", "");
    }
    final JsonNode failed = failure.get();
    if (failed != null) {
      out.put(SESSION_ID, captured.getOrDefault(SESSION_ID, ""));
      return fail(
          out, JsonPaths.first(failed, rules.failureText()), captured.getOrDefault(TEXT, ""));
    }
    final JsonNode r = result.get();
    if (r == null) {
      return fail(out, "the harness gave no result (exit code " + exitCode + ")", "");
    }
    return finish(out, r);
  }

  private Map<String, Object> finish(final Map<String, Object> out, final JsonNode r) {
    final String text = JsonPaths.firstOr(JsonPaths.first(r, rules.text()), captured.get(TEXT));
    out.put(
        SESSION_ID,
        JsonPaths.firstOr(JsonPaths.first(r, rules.sessionId()), captured.get(SESSION_ID)));
    final String kind = JsonPaths.first(r, rules.subtype());
    out.put(TEXT, text);
    if (rules.incompleteSubtypes().contains(kind)) {
      out.put(STATUS, "incomplete");
      out.put("reason", kind);
    } else if (Boolean.parseBoolean(JsonPaths.first(r, rules.isError()))) {
      out.put(STATUS, FAILURE);
      out.put("reason", text.isBlank() ? kind : text);
    } else {
      out.put(STATUS, "success");
    }
    return out;
  }

  private static Map<String, Object> fail(
      final Map<String, Object> out, final String reason, final String text) {
    out.put(STATUS, FAILURE);
    out.put("reason", reason);
    out.put(TEXT, text);
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
}
