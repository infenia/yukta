// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

/**
 * One thing wrong with a workflow definition.
 *
 * @param nodeId the node it is about, or {@code null} for the workflow as a whole
 * @param path where in the definition, such as {@code engine.retry.maxBackoffMs}, or {@code null}
 * @param code a stable machine-readable code, such as {@code DUPLICATE_NODE_ID}
 * @param message what is wrong, for a person
 */
public record ValidationProblem(String nodeId, String path, String code, String message) {

  /** A plugin's own complaint about one node; keeps its message and says which node. */
  /* default */ static IllegalArgumentException pluginError(
      final String nodeId, final String code, final IllegalArgumentException cause) {
    return new PluginProblemException(nodeId, code, cause);
  }

  /** The error form of a plugin problem, so {@code validate} still fails with the same message. */
  /* default */ static final class PluginProblemException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final transient ValidationProblem problem;

    /* default */ PluginProblemException(
        final String nodeId, final String code, final IllegalArgumentException cause) {
      super(cause.getMessage(), cause);
      this.problem =
          ValidationProblem.ofNode(
              nodeId, code, cause.getMessage() != null ? cause.getMessage() : cause.toString());
    }

    /* default */ ValidationProblem problem() {
      return problem;
    }
  }

  /** A problem about the workflow as a whole. */
  /* default */ static ValidationProblem of(final String code, final String message) {
    return new ValidationProblem(null, null, code, message);
  }

  /** A problem about one node. */
  /* default */ static ValidationProblem ofNode(
      final String nodeId, final String code, final String message) {
    return new ValidationProblem(nodeId, null, code, message);
  }
}
