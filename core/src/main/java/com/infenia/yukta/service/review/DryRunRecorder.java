// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;

/**
 * Where a dry-run supervisor's refusals are recorded so the cockpit can show them. {@link
 * ControlReviewer} calls it; the supervisor host implements it.
 */
@FunctionalInterface
public interface DryRunRecorder {

  /**
   * Records that a dry-run supervisor would have refused a command.
   *
   * @param supervisorId the supervisor
   * @param command the command it would have refused
   * @param reason why
   */
  void wouldHaveRefused(String supervisorId, ExecutionControlCommand command, String reason);
}
