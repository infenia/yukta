// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.experimental.UtilityClass;

/**
 * Builds the idempotency key of an input request.
 *
 * <p>The key does not contain the execution id. A resumed run gets a new one, and must find the
 * answer to a question that its earlier run already asked. A message id is only stable across a
 * resume when the message was replayed from a node that had finished, so a node that runs again
 * asks again.
 *
 * @since 0.1.0-alpha.4
 */
@UtilityClass
public class InputKeys {

  /** Separates the parts, so that {@code "a" + "bc"} and {@code "ab" + "c"} differ. */
  private static final char SEPARATOR = '\0';

  /**
   * The key of a question.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param nodeId the node that asks
   * @param messageId the id of the message the node was processing
   * @param name what the question is about, such as the signal name
   * @return the hex SHA-256 of the parts, 64 characters
   */
  @SuppressWarnings({"PMD.ShortMethodName", "PMD.UseObjectForClearerAPI"})
  public static String of(
      final String sessionId,
      final String workflowId,
      final String nodeId,
      final String messageId,
      final String name) {
    final String joined =
        String.join(
            String.valueOf(SEPARATOR),
            InputLimits.requireId("sessionId", sessionId),
            InputLimits.requireId("workflowId", workflowId),
            InputLimits.requireId("nodeId", nodeId),
            InputLimits.requireId("messageId", messageId),
            InputLimits.requireId("name", name));
    return hash("SHA-256", joined);
  }

  /** The hex of the hash of a text; every JVM has SHA-256, so that name never fails. */
  /* default */ static String hash(final String algorithm, final String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }
}
