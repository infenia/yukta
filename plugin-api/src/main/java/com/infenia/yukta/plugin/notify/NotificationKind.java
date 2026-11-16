// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

/**
 * What a notification asks of the person.
 *
 * @since 0.1.0-alpha.5
 */
public enum NotificationKind {

  /** An alert; nothing to answer. */
  INFO,

  /** A question: it points at an input request that a person may answer. */
  ACTION
}
