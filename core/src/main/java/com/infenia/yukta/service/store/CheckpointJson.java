// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON form of a checkpointed message that keeps the payload's Java type.
 *
 * <p>The message is written as usual, with the payload's class name added as {@code payloadType}.
 * On reading, the payload is rebuilt as that class only if its name starts with one of {@link
 * #ALLOWED}; the name comes from stored data, so it is never loaded blindly. Any other type, or a
 * payload that does not fit the class, comes back as the plain JSON value (map, list, text,
 * number), with a warning.
 */
@Slf4j
@UtilityClass
public class CheckpointJson {

  /** Name of the property holding the payload's class. */
  private static final String TYPE = "payloadType";

  /** Class name prefixes whose payloads are restored with their type. */
  // ponytail: fixed list; a setting for third-party plugin packages when someone needs one.
  private static final List<String> ALLOWED =
      List.of(
          "com.infenia.yukta.",
          "java.time.",
          "java.math.",
          "java.util.UUID",
          "java.lang.Long",
          "java.lang.Short",
          "java.lang.Byte",
          "java.lang.Float",
          "[B");

  /**
   * Writes a message with the payload's class.
   *
   * @param mapper the JSON mapper
   * @param message the message
   * @return one line of JSON
   */
  public static String write(final ObjectMapper mapper, final Message<?> message) {
    final JsonNode tree = mapper.valueToTree(message);
    if (message.payload() != null) {
      ((ObjectNode) tree).put(TYPE, message.payload().getClass().getName());
    }
    return mapper.writeValueAsString(tree);
  }

  /**
   * Reads a message written by {@link #write}, or an older one without a class.
   *
   * @param mapper the JSON mapper
   * @param json one line of JSON
   * @return the message, with the payload as its original type when that is allowed
   */
  public static Message<?> read(final ObjectMapper mapper, final String json) {
    final JsonNode tree = mapper.readTree(json);
    final Message<?> message = mapper.treeToValue(tree, DefaultMessage.class);
    final String type = tree.path(TYPE).asString(null);
    Message<?> result = message;
    if (type != null) {
      if (ALLOWED.stream().noneMatch(type::startsWith)) {
        log.atWarn().addKeyValue("type", type).log("Payload type not allowed, restored as JSON");
      } else {
        try {
          result =
              message.withPayload(mapper.treeToValue(tree.path("payload"), Class.forName(type)));
        } catch (final ClassNotFoundException | JacksonException e) {
          log.atWarn().addKeyValue("type", type).setCause(e).log("Payload restored as JSON");
        }
      }
    }
    return result;
  }
}
