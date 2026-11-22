// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One failure a {@link FailureExtractor} recognised in a node's output: a failed test, a compile
 * error, a lint violation or a failed build task.
 *
 * @param kind what failed
 * @param executionId the run whose log holds it
 * @param nodeId the node that wrote it
 * @param name the test, task or rule, or {@code null}
 * @param location {@code file:line}, or {@code null}
 * @param message the reason, cut to {@value #MAX_MESSAGE} characters
 * @param line its first line in the node's output, from 1, as node output reads count it
 */
public record Finding(
    Kind kind,
    String executionId,
    String nodeId,
    @Schema(nullable = true) @Nullable String name,
    @Schema(nullable = true) @Nullable String location,
    String message,
    long line) {

  /** Longest message kept. */
  public static final int MAX_MESSAGE = 300;

  /** Cuts the message. */
  public Finding {
    message = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) + "…" : message;
  }

  /** What failed. */
  public enum Kind {
    /** A test. */
    TEST,
    /** A compile error. */
    COMPILE,
    /** A lint or static-analysis violation. */
    LINT,
    /** A build task or package. */
    TASK,
    /** An error line nothing more specific recognised. */
    ERROR
  }
}
