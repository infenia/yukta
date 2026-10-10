// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** How a workflow execution is stopped. */
public enum StopMode {
  /**
   * Stop the triggers and let work already in flight finish. If it does not finish within the drain
   * deadline the execution is cancelled. This is the default.
   */
  DRAIN,
  /** Cancel every node's subscription at once, so plugins can clean up. */
  CANCEL,
  /** Cancel immediately and remove the execution, so no further commands are accepted for it. */
  KILL
}
