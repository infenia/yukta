// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import com.infenia.yukta.plugin.agent.AgentProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * An agent harness as data: how to start it, how a prompt, model and turn limit reach it, and how
 * to read its output. Adding a harness means adding a JSON file, not Java.
 *
 * @param id the name a node's {@code harness} option takes
 * @param protocol how Yukta talks to the harness; a file without {@code protocol} is {@code jsonl}
 * @param description what it is, and what a node must add to make it useful
 * @param command the command; {@code {prompt}} is replaced by the prompt
 * @param modelArgs arguments added for a {@code model} ({@code {model}}); empty if unsupported
 * @param maxTurnsArgs arguments added for {@code maxTurns} ({@code {maxTurns}}); empty if
 *     unsupported
 * @param resumeArgs arguments added to continue a session ({@code {session}}); empty if unsupported
 * @param capabilities the capabilities it declares ({@code stream}, {@code resume}, {@code ask})
 * @param output how to read the output
 * @param interactive how to hold a conversation over stdin (approvals); null if unsupported
 * @param authMethod the ACP method to {@code authenticate} with, or null when the agent is logged
 *     in already; only for protocol {@code acp}
 * @param pty how to drive a program in a terminal; only for protocol {@code pty}
 */
public record AgentPreset(
    String id,
    AgentProtocol protocol,
    String description,
    List<String> command,
    List<String> modelArgs,
    List<String> maxTurnsArgs,
    List<String> resumeArgs,
    Set<String> capabilities,
    Output output,
    @Nullable Interactive interactive,
    @Nullable String authMethod,
    @Nullable Pty pty) {

  /** Compact constructor to ensure immutability. */
  public AgentPreset {
    Objects.requireNonNull(protocol, "protocol");
    command = List.copyOf(command);
    modelArgs = List.copyOf(modelArgs);
    maxTurnsArgs = List.copyOf(maxTurnsArgs);
    resumeArgs = List.copyOf(resumeArgs);
    capabilities = Set.copyOf(capabilities);
  }

  /**
   * Whether a message can be written to the agent while it runs.
   *
   * @return true for a protocol that has a conversation of its own, or a preset with an {@code
   *     interactive} mode
   */
  public boolean converses() {
    return protocol != AgentProtocol.JSONL || interactive != null;
  }

  /**
   * How to drive a program in a terminal.
   *
   * @param type what is typed once the program is ready, to give it the prompt; {@code {prompt}} is
   *     the prompt. Not used when the command carries {@code {prompt}} as an argument
   * @param readyPattern a screen that matches says the program is ready for the prompt, or null
   * @param readyDelayMs how long to wait for it before typing the prompt anyway (never into a
   *     screen that wants attention: then the prompt waits until the screen stops wanting it)
   * @param submit what is typed after a message to send it
   * @param submitDelayMs above 0, the pause before {@code submit} is typed on its own, after the
   *     prompt and after each message; then {@code type} leaves the submit out. A program that
   *     takes a fast burst of keys as a paste (Claude Code) ignores an Enter that comes with it
   * @param interruptKeys what is typed to stop the program's turn
   * @param attentionPattern a screen that matches says the program waits for a person, or null
   * @param donePattern a screen that matches says the program is finished, or null
   */
  public record Pty(
      String type,
      @Nullable Pattern readyPattern,
      int readyDelayMs,
      String submit,
      int submitDelayMs,
      String interruptKeys,
      @Nullable Pattern attentionPattern,
      @Nullable Pattern donePattern) {

    /** How long the terminal gets to start before the prompt is typed. */
    public static final int DEFAULT_READY_DELAY_MS = 1500;
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
   * @param extras payload fields taken from the result line: name to dotted path (a number or an
   *     object keeps its type), for example the cost or a structured answer
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
      String failureText,
      Map<String, String> extras) {
    /** Copies the collections so the record stays immutable. */
    public Output {
      resultWhen = Map.copyOf(resultWhen);
      incompleteSubtypes = Set.copyOf(incompleteSubtypes);
      live = List.copyOf(live);
      captures = List.copyOf(captures);
      failureWhen = Map.copyOf(failureWhen);
      extras = Map.copyOf(extras);
    }
  }

  /**
   * A value kept from a line as it passes; the last one wins.
   *
   * @param when field values the line must have (keys are dotted paths)
   * @param path dotted path of the value
   * @param into {@code text} or {@code sessionId}
   */
  public record Capture(Map<String, String> when, String path, String into) {
    /** Copies the collections so the record stays immutable. */
    public Capture {
      when = Map.copyOf(when);
    }
  }

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
   * @param steer true when the harness takes a user message written during a turn
   * @param interrupt the line that stops the turn ({@code {id}} is a new request id), or a missing
   *     node when the harness cannot be interrupted
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
      JsonNode deny,
      boolean steer,
      JsonNode interrupt) {
    /** Copies the collections so the record stays immutable. */
    public Interactive {
      command = List.copyOf(command);
      requestWhen = Map.copyOf(requestWhen);
    }

    /**
     * Whether the harness can be interrupted.
     *
     * @return true when the preset has an {@code interrupt} line
     */
    public boolean canInterrupt() {
      return !interrupt.isMissingNode() && !interrupt.isNull();
    }
  }

  /**
   * A kind of line shown in the run log as it arrives.
   *
   * @param when field values the line must have
   * @param text dotted path of the text; {@code a[*].b} reads field b of every element of a
   * @param prefix text put before each value in the run log
   * @param kind what a match is in the conversation; by default a rule with a prefix is a tool call
   *     and any other rule is a message
   */
  public record Live(Map<String, String> when, String text, String prefix, Kind kind) {

    /** What a matching line is in the conversation. */
    public enum Kind {
      /** Text of the agent. */
      MESSAGE,
      /** The agent's reasoning. */
      THOUGHT,
      /** A tool call; the matched text is its title. */
      TOOL
    }

    /** Copies the collections so the record stays immutable. */
    public Live {
      when = Map.copyOf(when);
      Objects.requireNonNull(kind, "kind");
    }
  }

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
    final List<Live> live = out.path("live").valueStream().map(AgentPreset::live).toList();
    return new AgentPreset(
        id,
        protocol(root.path("protocol"), id),
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
            out.path("failureText").asString(""),
            strings(out.path("extras"))),
        interactive(root.path("interactive")),
        authMethod(root.path("acp").path("authMethod")),
        pty(root.path("pty")));
  }

  private static Live live(final JsonNode node) {
    final String prefix = node.path("prefix").asString("");
    final JsonNode kind = node.path("kind");
    return new Live(
        strings(node.path("when")),
        required(node, "text"),
        prefix,
        kind.isMissingNode() || kind.isNull()
            ? (prefix.isEmpty() ? Live.Kind.MESSAGE : Live.Kind.TOOL)
            : parseKind(kind.asString(""), "live.kind"));
  }

  private static Live.Kind parseKind(final String text, final String field) {
    try {
      return Live.Kind.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "agent harness preset: '"
              + field
              + "' must be message, thought or tool, not '"
              + text
              + "'",
          e);
    }
  }

  private static AgentProtocol protocol(final JsonNode node, final String id) {
    if (node.isMissingNode() || node.isNull()) {
      return AgentProtocol.JSONL;
    }
    try {
      return AgentProtocol.valueOf(node.asString("").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "agent harness preset '"
              + id
              + "': 'protocol' must be jsonl, acp or pty, not '"
              + node.asString("")
              + "'",
          e);
    }
  }

  private static List<Capture> captures(final JsonNode node) {
    final List<Capture> found = new ArrayList<>();
    node.forEach(n ->
        found.add(new Capture(strings(n.path("when")), required(n, "path"), required(n, "into"))));
    return found;
  }

  private static @Nullable Pty pty(final JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return new Pty(
        node.path("type").asString("{prompt}\r"),
        pattern(node, "readyPattern"),
        node.path("readyDelayMs").asInt(Pty.DEFAULT_READY_DELAY_MS),
        node.path("submit").asString("\r"),
        node.path("submitDelayMs").asInt(0),
        node.path("interruptKeys").asString("\u0003"),
        pattern(node, "attentionPattern"),
        pattern(node, "donePattern"));
  }

  private static @Nullable Pattern pattern(final JsonNode node, final String field) {
    final JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull() || value.asString("").isEmpty()) {
      return null;
    }
    try {
      return Pattern.compile(value.asString(), Pattern.MULTILINE);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          "agent harness preset: '" + field + "' is not a regular expression: " + e.getMessage(),
          e);
    }
  }

  private static @Nullable String authMethod(final JsonNode node) {
    return node.isString() && !node.asString().isBlank() ? node.asString() : null;
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
        node.path("deny"),
        node.path("steer").asBoolean(false),
        node.path("interrupt"));
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

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<String, String> strings(final JsonNode node) {
    final Map<String, String> values = new java.util.LinkedHashMap<>();
    node.properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
    return values;
  }
}
