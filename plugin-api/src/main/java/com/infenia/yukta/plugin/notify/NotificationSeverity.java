// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

/**
 * How serious a notification is; the same names as the severity of an execution event.
 *
 * @since 0.1.0-alpha.5
 */
public enum NotificationSeverity {

  /** Good to know. */
  INFO,

  /** Needs a look. */
  WARN,

  /** Something failed or is blocked. */
  ERROR
}
