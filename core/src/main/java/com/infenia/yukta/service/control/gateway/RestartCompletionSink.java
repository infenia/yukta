// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

/**
 * Callback used by restart command processors to report the outcome of an in-flight restart back to
 * the {@link ControlBusGateway} caller awaiting it.
 *
 * <p>{@link #completeRestartSuccess} and {@link #completeRestartFailure} are best-effort: if the
 * caller has already timed out and the pending entry was removed, both are no-ops.
 */
public interface RestartCompletionSink {

  /**
   * Reports that a restart succeeded and the new execution has been subscribed.
   *
   * @param newExecutionId the identifier of the restarted execution
   */
  void completeRestartSuccess(String newExecutionId);

  /**
   * Reports that a restart failed before the new execution could be started.
   *
   * @param newExecutionId the identifier that was reserved for the restarted execution
   * @param error the failure
   */
  void completeRestartFailure(String newExecutionId, Throwable error);
}
