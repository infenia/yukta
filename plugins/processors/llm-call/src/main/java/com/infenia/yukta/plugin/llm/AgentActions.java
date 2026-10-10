// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.ArrayList;
import java.util.List;
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
