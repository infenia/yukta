// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import org.jspecify.annotations.Nullable;

/**
 * Which notifications to find. A {@code null} field does not filter.
 *
 * @param state only this state
 * @param kind only this kind
 * @param sessionId only this session
 * @param sinceSeq only changes after this sequence; 0 for all
 */
public record NotificationQuery(
    Notification.@Nullable State state,
    @Nullable NotificationKind kind,
    @Nullable String sessionId,
    long sinceSeq) {

  /** Everything. */
  public static final NotificationQuery ALL = new NotificationQuery(null, null, null, 0);

  /**
   * Everything after a sequence.
   *
   * @param seq the last sequence the reader has
   * @return the query
   */
  public static NotificationQuery since(final long seq) {
    return new NotificationQuery(null, null, null, seq);
  }
}
