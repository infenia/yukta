// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Extracts {@code <agent_action>} blocks from a model reply. */
@UtilityClass
public class AgentActions {

  /** Matches one action block, across lines. */
  private static final Pattern BLOCK =
      Pattern.compile("<agent_action>(.*?)</agent_action>", Pattern.DOTALL);

  /** Parses action bodies. */
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  /**
   * Parsed reply.
   *
   * @param content the reply text with action blocks removed
   * @param actions each block parsed as JSON; a block that is not valid JSON is kept as its raw
   *     text
   */
  public record Parsed(String content, List<Object> actions) {

    /** Defensively copies the actions. */
    public Parsed {
      actions = List.copyOf(actions);
    }
  }

  /**
   * Turns a tool call that the model asked for natively into the action shape used for the tags:
   * {@code {tool, arguments, id}}, with the arguments parsed when they are JSON.
   *
   * @param call the tool call
   * @return the action
   */
  public static Object fromToolCall(final ToolCallRequest call) {
    Object arguments;
    try {
      arguments = JSON_MAPPER.readValue(call.arguments(), Object.class);
    } catch (JacksonException e) {
      arguments = call.arguments();
    }
    return Map.of(
        "tool",
        call.name(),
        "arguments",
        Objects.requireNonNullElse(arguments, ""),
        "id",
        Objects.toString(call.id(), ""));
  }

  /**
   * Turns the {@code actions} of an earlier answer back into the tool calls the model asked for, so
   * they can be sent with the assistant turn that carries them.
   *
   * @param actions a list of {@code {tool, arguments, id}} maps, as produced by {@link
   *     #fromToolCall}; anything else yields no calls
   * @return the calls, arguments as JSON text
   */
  public static List<ToolCallRequest> toToolCalls(final Object actions) {
    final List<ToolCallRequest> calls = new ArrayList<>();
    if (actions instanceof final List<?> list) {
      for (final Object item : list) {
        if (item instanceof final Map<?, ?> action && action.get("tool") != null) {
          calls.add(
              new ToolCallRequest(
                  Objects.toString(action.get("id"), ""),
                  String.valueOf(action.get("tool")),
                  argumentsJson(action.get("arguments"))));
        }
      }
    }
    return calls;
  }

  private static String argumentsJson(final Object arguments) {
    if (arguments instanceof final String text) {
      return text;
    }
    try {
      return JSON_MAPPER.writeValueAsString(Objects.requireNonNullElse(arguments, Map.of()));
    } catch (JacksonException e) {
      return "{}";
    }
  }

  /**
   * Split a reply into visible text and actions.
   *
   * @param text the raw model reply
   * @return the parsed reply
   */
  public static Parsed parse(final String text) {
    final List<Object> actions = new ArrayList<>();
    final var matcher = BLOCK.matcher(text);
    while (matcher.find()) {
      final String body = matcher.group(1).strip();
      try {
        actions.add(JSON_MAPPER.readValue(body, Object.class));
      } catch (JacksonException e) {
        actions.add(body);
      }
    }
    return new Parsed(BLOCK.matcher(text).replaceAll("").strip(), actions);
  }
}
