// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.model.notify.NotificationRuleSettings;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Who sees a notification. A caller sees one when the session is reachable, a selector matches, and
 * for an action the caller is a person (unless a selector names the key itself). Seeing an item
 * never grants the right to answer it.
 */
@UtilityClass
public class Recipients {

  /** The shared inbox: an open server and a key without a name. */
  public static final String LOCAL = "local";

  /** The name {@code ApiKeys} gives a key that has none. */
  private static final String UNNAMED = "unnamed";

  private static final String ROLE = "role:";
  private static final String KEY = "key:";

  /**
   * Whether a text is a selector of a notification audience.
   *
   * @param selector the text
   * @return true for {@code role:VIEWER|OPERATOR|ADMIN} and {@code key:<name>}
   */
  public static boolean isSelector(final @Nullable String selector) {
    return NotificationRuleSettings.isSelector(selector);
  }

  /**
   * The recipient id of a caller.
   *
   * @param caller the resolved key; {@code null} on an open server
   * @return the key name, or {@link #LOCAL} for no key and for a key without a name
   */
  public static String recipient(final ApiKeys.@Nullable Match caller) {
    return caller == null || UNNAMED.equals(caller.name()) ? LOCAL : caller.name();
  }

  /**
   * Whether a caller sees a notification.
   *
   * @param notification the notification
   * @param caller the resolved key; {@code null} on an open server, which sees everything it is
   *     addressed to as an administrator
   * @return true if the caller sees it
   */
  public static boolean sees(
      final Notification notification, final ApiKeys.@Nullable Match caller) {
    final String sessionId = notification.sessionId();
    if (caller != null && sessionId != null && !caller.reaches(sessionId)) {
      return false;
    }
    final ApiRole role = caller == null ? ApiRole.ADMIN : caller.role();
    final String me = recipient(caller);
    final boolean person = caller == null || !caller.agent();
    final boolean action = notification.kind() == NotificationKind.ACTION;
    for (final String selector : notification.audience()) {
      if (selects(selector, me, role, person || !action)) {
        return true;
      }
    }
    return false;
  }

  /** Whether one selector lets this caller in: its key, or a role at or below the caller's. */
  private static boolean selects(
      final String selector, final String me, final ApiRole role, final boolean mayAct) {
    if (selector.startsWith(KEY)) {
      return selector.substring(KEY.length()).equals(me);
    }
    return mayAct
        && selector.startsWith(ROLE)
        && role.compareTo(ApiRole.valueOf(selector.substring(ROLE.length()))) >= 0;
  }
}
