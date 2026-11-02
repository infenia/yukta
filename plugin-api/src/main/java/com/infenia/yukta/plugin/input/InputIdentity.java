// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import reactor.util.context.ContextView;

/**
 * The run and node that ask, read from the Reactor context the engine writes for every node. A
 * plugin cannot choose these values.
 *
 * @param sessionId the session of the run
 * @param workflowId the workflow of the run
 * @param executionId the run
 * @param nodeId the node
 * @since 0.1.0-alpha.4
 */
public record InputIdentity(
    String sessionId, String workflowId, String executionId, String nodeId) {

  /** Context key of the session id; the same string as the engine's {@code CTX_SESSION_ID}. */
  public static final String SESSION_ID_KEY = "sessionId";

  /** Context key of the workflow id. */
  public static final String WORKFLOW_ID_KEY = "workflowId";

  /** Context key of the execution id. */
  public static final String EXECUTION_ID_KEY = "executionId";

  /** Context key of the node id. */
  public static final String NODE_ID_KEY = "nodeId";

  /**
   * Checks the parts.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param executionId the run
   * @param nodeId the node
   */
  public InputIdentity {
    InputLimits.requireId("sessionId", sessionId);
    InputLimits.requireId("workflowId", workflowId);
    InputLimits.requireId("executionId", executionId);
    InputLimits.requireId("nodeId", nodeId);
  }

  /**
   * Reads the identity from the context of a running node.
   *
   * @param context the Reactor context
   * @return the identity
   * @throws IllegalStateException if a key is missing, which means the code does not run inside a
   *     node
   */
  public static InputIdentity from(final ContextView context) {
    return new InputIdentity(
        read(context, SESSION_ID_KEY),
        read(context, WORKFLOW_ID_KEY),
        read(context, EXECUTION_ID_KEY),
        read(context, NODE_ID_KEY));
  }

  private static String read(final ContextView context, final String key) {
    final Object value = context.getOrDefault(key, null);
    if (value instanceof final String text && !text.isBlank()) {
      return text;
    }
    throw new IllegalStateException(
        "No '" + key + "' in the Reactor context: input can only be asked while a node runs");
  }
}
