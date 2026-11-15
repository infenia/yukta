// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An alert a workflow author asks for.
 *
 * @param title one line, at most {@link Notification#TITLE_MAX} characters
 * @param body plain text, or {@code null}
 * @param severity how serious it is
 * @param audience selectors {@code role:VIEWER|OPERATOR|ADMIN} or {@code key:<name>}; empty means
 *     {@code role:OPERATOR}
 * @param key dedupe key, or {@code null} for a new notification each time
 * @param channels outbound channels besides the inbox, as {@code <type>:<name>}
 * @since 0.1.0-alpha.5
 */
public record NotificationSpec(
    String title,
    @Nullable String body,
    NotificationSeverity severity,
    List<String> audience,
    @Nullable String key,
    List<String> channels) {

  /** Checks the required fields and keeps immutable copies. */
  public NotificationSpec {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(severity, "severity");
    audience = List.copyOf(audience);
    channels = List.copyOf(channels);
  }
}
