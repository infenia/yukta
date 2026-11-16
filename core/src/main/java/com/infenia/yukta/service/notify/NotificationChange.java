// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.Notification;
import org.jspecify.annotations.Nullable;

/**
 * One change, for the stream.
 *
 * @param kind what happened
 * @param notification the notification; {@code null} for a receipt of a removed one
 * @param receipt the receipt, for {@link Kind#RECEIPT}
 */
public record NotificationChange(
    Kind kind, @Nullable Notification notification, @Nullable Receipt receipt) {

  /** What happened. */
  public enum Kind {

    /** A new notification. */
    CREATED,

    /** A notification changed: its count, text or audience. */
    UPDATED,

    /** A notification ended. */
    RESOLVED,

    /** Someone read or dismissed one. */
    RECEIPT
  }
}
