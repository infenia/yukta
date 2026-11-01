// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who may answer a request. An empty audience means "whoever the current tier of the route
 * targets".
 *
 * <p>A selector is one of {@code supervisor:<id>}, {@code supervisor:*}, {@code role:OPERATOR},
 * {@code role:ADMIN}, {@code key:<name>} or {@code external:<tier>}. {@code role:OPERATOR} matches
 * operators and admins.
 *
 * @param selectors the selectors, in any order
 * @since 0.1.0-alpha.4
 */
public record Audience(List<String> selectors) {

  /** Most selectors an audience can list. */
  private static final int MAX_SELECTORS = 20;

  /** The accepted shapes of a selector. */
  private static final Pattern SELECTOR =
      Pattern.compile(
          "supervisor:(\\*|[A-Za-z0-9._-]{1,64})|role:(OPERATOR|ADMIN)|key:\\S{1,128}"
              + "|external:[A-Za-z0-9._-]{1,64}");

  /**
   * The audience that defers to the current tier. Declared after the fields the constructor reads,
   * so they are assigned when it runs.
   */
  public static final Audience CURRENT_TIER = new Audience(List.of());

  /**
   * Checks the selectors and keeps an immutable copy.
   *
   * @param selectors the selectors
   */
  public Audience {
    Objects.requireNonNull(selectors, "selectors");
    if (selectors.size() > MAX_SELECTORS) {
      throw new IllegalArgumentException(
          "An audience lists at most " + MAX_SELECTORS + " selectors");
    }
    for (final String selector : selectors) {
      if (selector == null || !SELECTOR.matcher(selector).matches()) {
        throw new IllegalArgumentException(
            "Not an audience selector: '"
                + selector
                + "'; use supervisor:<id>, supervisor:*, role:OPERATOR, role:ADMIN, key:<name> or"
                + " external:<tier>");
      }
    }
    selectors = List.copyOf(selectors);
  }

  /**
   * Whether the audience defers to the current tier.
   *
   * @return true if no selector is listed
   */
  public boolean isCurrentTier() {
    return selectors.isEmpty();
  }
}
