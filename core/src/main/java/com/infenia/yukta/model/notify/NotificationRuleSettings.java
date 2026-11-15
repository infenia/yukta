// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.notify;

import com.infenia.yukta.plugin.notify.NotificationSeverity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One notification rule of {@code yukta.notifications.rules}: which events raise a notification,
 * for whom, and through which outbound channels besides the inbox.
 *
 * @param category what raises it: {@code input}, {@code run.failed}, {@code run.finished}, {@code
 *     run.held}, {@code delivery.failed} or the author's category
 * @param sessions session globs the rule is limited to; empty means all
 * @param workflows workflow globs the rule is limited to; empty means all
 * @param audience selectors {@code role:VIEWER|OPERATOR|ADMIN} or {@code key:<name>}; empty keeps
 *     the category's default
 * @param severity overrides the severity, or {@code null}
 * @param channels outbound channels as {@code <type>:<name>}, each configured under {@code
 *     yukta.notifications.channels}
 * @param cooldown a repeat of the same key inside this time raises the count; zero for none
 */
public record NotificationRuleSettings(
    @Nullable String category,
    @DefaultValue List<String> sessions,
    @DefaultValue List<String> workflows,
    @DefaultValue List<String> audience,
    @Nullable NotificationSeverity severity,
    @DefaultValue List<String> channels,
    @DefaultValue("0s") Duration cooldown) {

  /** The categories a rule may name. */
  public static final Set<String> CATEGORIES =
      Set.of("input", "run.failed", "run.finished", "run.held", "delivery.failed", "workflow");

  /** The accepted shapes of an audience selector. */
  private static final Pattern SELECTOR =
      Pattern.compile("role:(VIEWER|OPERATOR|ADMIN)|key:\\S{1,128}");

  /** Keeps immutable copies. */
  public NotificationRuleSettings {
    sessions = List.copyOf(sessions);
    workflows = List.copyOf(workflows);
    audience = List.copyOf(audience);
    channels = List.copyOf(channels);
  }

  /**
   * Whether a text is a selector of a notification audience.
   *
   * @param selector the text
   * @return true for {@code role:VIEWER|OPERATOR|ADMIN} and {@code key:<name>}
   */
  public static boolean isSelector(final @Nullable String selector) {
    return selector != null && SELECTOR.matcher(selector).matches();
  }

  /**
   * Checks the rule at start-up.
   *
   * @param name the rule's name, for the message
   * @param configured the configured channels: type, then name
   * @throws IllegalArgumentException naming the rule, for an unknown category, a bad selector, a
   *     channel that is not configured or a negative cooldown
   */
  public void check(final String name, final Map<String, ? extends Map<String, ?>> configured) {
    final String where = "yukta.notifications.rules." + name;
    if (category == null || !CATEGORIES.contains(category)) {
      throw new IllegalArgumentException(
          where + ".category must be one of " + CATEGORIES.stream().sorted().toList());
    }
    for (final String selector : audience) {
      if (!isSelector(selector)) {
        throw new IllegalArgumentException(
            where
                + ".audience: '"
                + selector
                + "' is not role:VIEWER|OPERATOR|ADMIN or key:<name>");
      }
    }
    if (cooldown.isNegative()) {
      throw new IllegalArgumentException(where + ".cooldown must not be negative");
    }
    for (final String channel : channels) {
      final int colon = channel.indexOf(':');
      final Map<String, ?> ofType = colon < 0 ? null : configured.get(channel.substring(0, colon));
      if (ofType == null || !ofType.containsKey(channel.substring(colon + 1))) {
        throw new IllegalArgumentException(
            where
                + ".channels: '"
                + channel
                + "' is not configured under"
                + " yukta.notifications.channels.<type>.<name>");
      }
    }
  }
}
