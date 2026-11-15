// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides whether an engine event raises an info notification and for whom. A small matcher of its
 * own: category, session and workflow globs. The supervisor rule engine lives in a plugin and core
 * cannot use it.
 *
 * <p>With no rule for a category, the built-in default applies: {@code run.failed} is on for
 * operators, every other category is off. With rules for a category, only a rule that matches the
 * run raises; the first by name wins.
 */
@Component
public class NotificationRules {

  /** The category of a root run that failed. */
  public static final String RUN_FAILED = "run.failed";

  /** The category of a root run that succeeded. */
  public static final String RUN_FINISHED = "run.finished";

  /** The category of a root run a supervisor paused or stopped. */
  public static final String RUN_HELD = "run.held";

  /** Who is told when a rule names nobody. */
  private static final List<String> OPERATORS = List.of("role:OPERATOR");

  /** The configured rules by name, so the first match is the same on every start. */
  private final Map<String, NotificationRuleSettings> rules;

  /** What raises a notification, and how. */
  public record Applied(
      List<String> audience,
      @Nullable NotificationSeverity severity,
      Duration cooldown,
      List<String> channels) {

    /** Keeps immutable copies. */
    public Applied {
      audience = List.copyOf(audience);
      channels = List.copyOf(channels);
    }
  }

  /**
   * Creates the matcher.
   *
   * @param properties the {@code yukta.notifications} settings
   */
  public NotificationRules(final YuktaProperties properties) {
    this.rules = new TreeMap<>(properties.notifications().rules());
  }

  /**
   * What an event of a category in a run raises.
   *
   * @param category {@link #RUN_FAILED}, {@link #RUN_FINISHED} or {@link #RUN_HELD}
   * @param sessionId the session of the run, or {@code null}
   * @param workflowId the workflow of the run, or {@code null}
   * @return how to raise it; empty when nothing should be raised
   */
  public Optional<Applied> forRun(
      final String category, final @Nullable String sessionId, final @Nullable String workflowId) {
    final List<NotificationRuleSettings> ofCategory =
        rules.values().stream().filter(rule -> category.equals(rule.category())).toList();
    if (ofCategory.isEmpty()) {
      return RUN_FAILED.equals(category)
          ? Optional.of(new Applied(OPERATORS, null, Duration.ZERO, List.of()))
          : Optional.empty();
    }
    return ofCategory.stream()
        .filter(
            rule -> matches(rule.sessions(), sessionId) && matches(rule.workflows(), workflowId))
        .findFirst()
        .map(rule ->
            new Applied(
                rule.audience().isEmpty() ? OPERATORS : rule.audience(),
                rule.severity(),
                rule.cooldown(),
                rule.channels()));
  }

  /** An empty list matches everything; otherwise one glob must match the value. */
  private static boolean matches(final List<String> globs, final @Nullable String value) {
    return globs.isEmpty()
        || value != null && globs.stream().anyMatch(glob -> glob(glob).matcher(value).matches());
  }

  /** {@code *} matches any run of characters and {@code ?} one; everything else is literal. */
  private static Pattern glob(final String glob) {
    final StringBuilder regex = new StringBuilder();
    for (final String part : glob.split("(?<=[*?])|(?=[*?])")) {
      regex.append(
          switch (part) {
            case "*" -> ".*";
            case "?" -> ".";
            default -> Pattern.quote(part);
          });
    }
    return Pattern.compile(regex.toString());
  }
}
