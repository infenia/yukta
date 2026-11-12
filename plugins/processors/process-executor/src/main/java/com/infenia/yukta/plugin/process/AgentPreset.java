// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * @param capabilities the capabilities it declares ({@code stream}, {@code resume}, {@code ask})
 * @param output how to read the output
 */
public record AgentPreset(
    String id,
    String description,
    List<String> command,
    List<String> modelArgs,
    List<String> maxTurnsArgs,
    Set<String> capabilities,
    Output output) {

  /** Compact constructor to ensure immutability. */
  public AgentPreset {
    command = List.copyOf(command);
    modelArgs = List.copyOf(modelArgs);
    maxTurnsArgs = List.copyOf(maxTurnsArgs);
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
   */
  public record Output(
      Map<String, String> resultWhen,
      String text,
      String sessionId,
      String isError,
      String subtype,
      Set<String> incompleteSubtypes,
      List<Live> live) {}

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
            live));
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
