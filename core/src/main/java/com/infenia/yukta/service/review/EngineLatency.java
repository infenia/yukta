// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

/**
 * How long the engine itself took on one step, apart from what the plugins did.
 *
 * @param executionId the run
 * @param sessionId its session
 * @param workflowId its workflow
 * @param stage which step was timed
 * @param ms the time in milliseconds
 */
public record EngineLatency(
    String executionId, String sessionId, String workflowId, Stage stage, double ms) {

  /** The steps the engine times. */
  public enum Stage {
    /** From the run being registered to its first plugin being called, without the queue wait. */
    START,
    /** From a message taking the node's turn to the plugin being called with it. */
    HANDOFF
  }
}
