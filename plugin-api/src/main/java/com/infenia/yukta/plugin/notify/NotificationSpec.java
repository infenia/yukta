// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
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

  /** The accepted shapes of an audience selector. */
  private static final Pattern SELECTOR =
      Pattern.compile("role:(VIEWER|OPERATOR|ADMIN)|key:\\S{1,128}");

  /** Checks the required fields and the selectors, and keeps immutable copies. */
  public NotificationSpec {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(severity, "severity");
    if (title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    for (final String selector : audience) {
      if (selector == null || !SELECTOR.matcher(selector).matches()) {
        throw new IllegalArgumentException(
            "Not an audience selector: '"
                + selector
                + "'; use role:VIEWER, role:OPERATOR, role:ADMIN or key:<name>");
      }
    }
    audience = List.copyOf(audience);
    channels = List.copyOf(channels);
  }
}
