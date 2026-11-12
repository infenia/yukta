// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * An agent harness as data: how to start it, how a prompt, model and turn limit reach it, and how
 * to read its output. Adding a harness means adding a JSON file, not Java.
 *
 * @param id the name a node's {@code harness} option takes
 * @param description what it is, and what a node must add to make it useful
 * @param command the command; {@code {prompt}} is replaced by the prompt
 * @param modelArgs arguments added for a {@code model} ({@code {model}}); empty if unsupported
 * @param maxTurnsArgs arguments added for {@code maxTurns} ({@code {maxTurns}}); empty if
 *     unsupported
 * @param resumeArgs arguments added to continue a session ({@code {session}}); empty if unsupported
 * @param capabilities the capabilities it declares ({@code stream}, {@code resume}, {@code ask})
 * @param output how to read the output
 * @param interactive how to hold a conversation over stdin (approvals); null if unsupported
 */
public record AgentPreset(
    String id,
    String description,
    List<String> command,
    List<String> modelArgs,
    List<String> maxTurnsArgs,
    List<String> resumeArgs,
    Set<String> capabilities,
    Output output,
    @Nullable Interactive interactive) {

  /** Compact constructor to ensure immutability. */
  public AgentPreset {
    command = List.copyOf(command);
    modelArgs = List.copyOf(modelArgs);
    maxTurnsArgs = List.copyOf(maxTurnsArgs);
    resumeArgs = List.copyOf(resumeArgs);
    capabilities = Set.copyOf(capabilities);
  }

  /**
   * How to read a harness' JSON-lines output.
   *
   * @param resultWhen field values that mark the final result line
   * @param text dotted path of the answer text in the result line
   * @param sessionId dotted path of the session id in the result line
   * @param isError dotted path of the error flag in the result line
   * @param subtype dotted path of the result kind in the result line
   * @param incompleteSubtypes result kinds that mean "stopped early" and not "failed"
   * @param live lines worth showing while the agent works
   * @param captures values taken from earlier lines when the result line lacks them
   * @param failureWhen field values that mark a failure line (a harness that reports one)
   * @param failureText dotted path of the reason in the failure line
   */
  public record Output(
      Map<String, String> resultWhen,
      String text,
      String sessionId,
      String isError,
      String subtype,
      Set<String> incompleteSubtypes,
      List<Live> live,
      List<Capture> captures,
      Map<String, String> failureWhen,
      String failureText) {}

  /**
   * A value kept from a line as it passes; the last one wins.
   *
   * @param when field values the line must have (keys are dotted paths)
   * @param path dotted path of the value
   * @param into {@code text} or {@code sessionId}
   */
  public record Capture(Map<String, String> when, String path, String into) {}

  /**
   * A harness run that holds a conversation over stdin: the prompt goes in as a message and the
   * harness sends requests (for example to use a tool) that are answered on stdin.
   *
   * @param command replaces {@code command}; has no {@code {prompt}}
   * @param userMessage the message that carries the prompt ({@code {prompt}} is replaced)
   * @param requestWhen field values that mark a request line
   * @param requestId dotted path of the request id
   * @param tool dotted path of what is asked for (the tool name)
   * @param input dotted path of the request details (an object)
   * @param description dotted path of a human-readable description
   * @param allow the reply that approves ({@code {id}} and {@code {input}} are replaced)
   * @param deny the reply that refuses ({@code {id}} and {@code {message}} are replaced)
   */
  public record Interactive(
      List<String> command,
      JsonNode userMessage,
      Map<String, String> requestWhen,
      String requestId,
      String tool,
      String input,
      String description,
      JsonNode allow,
      JsonNode deny) {}

  /**
   * A kind of line shown in the run log as it arrives.
   *
   * @param when field values the line must have
   * @param text dotted path of the text; {@code a[*].b} reads field b of every element of a
   * @param prefix text put before each value
   */
  public record Live(Map<String, String> when, String text, String prefix) {}

  /**
   * Reads a preset from its JSON.
   *
   * @param root the parsed file
   * @return the preset
   * @throws IllegalArgumentException when a required field is missing
   */
  public static AgentPreset parse(final JsonNode root) {
    final String id = required(root, "id");
    final JsonNode out = root.path("output");
    final List<Live> live = new ArrayList<>();
    out.path("live")
        .forEach(
            n ->
                live.add(
                    new Live(
                        strings(n.path("when")),
                        required(n, "text"),
                        n.path("prefix").asString(""))));
    return new AgentPreset(
        id,
        root.path("description").asString(""),
        list(root.path("command")),
        list(root.path("modelArgs")),
        list(root.path("maxTurnsArgs")),
        list(root.path("resumeArgs")),
        Set.copyOf(
            root.path("capabilities").properties().stream()
                .filter(e -> e.getValue().asBoolean(false))
                .map(Map.Entry::getKey)
                .toList()),
        new Output(
            strings(out.path("resultWhen")),
            out.path("text").asString(""),
            out.path("sessionId").asString(""),
            out.path("isError").asString(""),
            out.path("subtype").asString(""),
            Set.copyOf(list(out.path("incompleteSubtypes"))),
            live,
            captures(out.path("captures")),
            strings(out.path("failureWhen")),
            out.path("failureText").asString("")),
        interactive(root.path("interactive")));
  }

  private static List<Capture> captures(final JsonNode node) {
    final List<Capture> found = new ArrayList<>();
    node.forEach(
        n ->
            found.add(
                new Capture(strings(n.path("when")), required(n, "path"), required(n, "into"))));
    return found;
  }

  private static @Nullable Interactive interactive(final JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return new Interactive(
        list(node.path("command")),
        node.path("userMessage"),
        strings(node.path("requestWhen")),
        required(node, "requestId"),
        required(node, "tool"),
        required(node, "input"),
        node.path("description").asString(""),
        node.path("allow"),
        node.path("deny"));
  }

  private static String required(final JsonNode node, final String field) {
    final String value = node.path(field).asString("");
    if (value.isBlank()) {
      throw new IllegalArgumentException("agent harness preset: '" + field + "' is required");
    }
    return value;
  }

  private static List<String> list(final JsonNode node) {
    final List<String> values = new ArrayList<>();
    node.forEach(n -> values.add(n.asString()));
    return values;
  }

  private static Map<String, String> strings(final JsonNode node) {
    final Map<String, String> values = new java.util.LinkedHashMap<>();
    node.properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
    return values;
  }
}
