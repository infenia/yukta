// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The settings of a profile of type {@code composite}, checked once.
 *
 * @param mode how the children are combined
 * @param children the profile ids of the children, in order
 * @param review how their reviews add up
 * @param quorum for {@link CompositeMode#QUORUM}, how many must agree
 * @param window for {@link CompositeMode#QUORUM}, how long equal commands are collected
 */
public record CompositeSettings(
    CompositeMode mode, List<String> children, ReviewMode review, int quorum, Duration window) {

  /** The most children one composite may have. */
  public static final int MAX_CHILDREN = 10;

  /** The deepest nesting of composites in composites, the outer one included. */
  public static final int MAX_DEPTH = 3;

  private static final Duration DEFAULT_WINDOW = Duration.ofMillis(500);

  /**
   * Keeps an immutable copy of the children.
   *
   * @param mode how the children are combined
   * @param children the child profile ids
   * @param review how their reviews add up
   * @param quorum how many must agree
   * @param window how long equal commands are collected
   */
  public CompositeSettings {
    children = List.copyOf(children);
  }

  /**
   * Reads and checks the settings.
   *
   * @param profile the profile
   * @return the settings
   * @throws IllegalArgumentException if a setting is missing or out of range
   */
  public static CompositeSettings read(final SupervisorProfile profile) {
    final Map<String, Object> settings = profile.settings();
    final CompositeMode mode = enumOf(CompositeMode.class, settings.get("mode"), "mode", null);
    final List<String> children = children(settings.get("children"));
    final ReviewMode review =
        enumOf(ReviewMode.class, settings.get("review"), "review", ReviewMode.ALL);
    final int quorum = quorum(settings.get("quorum"), mode, children.size());
    return new CompositeSettings(mode, children, review, quorum, window(settings.get("window")));
  }

  @SuppressWarnings("PMD.CyclomaticComplexity")
  private static List<String> children(final Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("Setting 'children' must be a list of profile ids");
    }
    if (list.size() > MAX_CHILDREN) {
      throw new IllegalArgumentException("A composite has at most " + MAX_CHILDREN + " children");
    }
    final List<String> ids = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    for (final Object item : list) {
      if (!(item instanceof String id) || id.isBlank() || !seen.add(id)) {
        throw new IllegalArgumentException("Children must be distinct, non-blank profile ids");
      }
      ids.add(id);
    }
    return ids;
  }

  private static int quorum(final Object value, final CompositeMode mode, final int children) {
    if (mode != CompositeMode.QUORUM) {
      return 0;
    }
    final int quorum = value == null ? children / 2 + 1 : number(value, "quorum");
    if (quorum < 2 || quorum > children) {
      throw new IllegalArgumentException(
          "Setting 'quorum' must be between 2 and the number of children (" + children + ")");
    }
    return quorum;
  }

  private static Duration window(final Object value) {
    if (value == null) {
      return DEFAULT_WINDOW;
    }
    final int millis = number(value, "window");
    if (millis < 1 || millis > 60_000) {
      throw new IllegalArgumentException("Setting 'window' is in milliseconds, 1 to 60000");
    }
    return Duration.ofMillis(millis);
  }

  private static int number(final Object value, final String name) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException("Setting '" + name + "' must be a number");
  }

  private static <E extends Enum<E>> E enumOf(
      final Class<E> type, final Object value, final String name, final E fallback) {
    if (value == null && fallback != null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, String.valueOf(value).strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Setting '" + name + "' must be one of " + List.of(type.getEnumConstants()), e);
    }
  }
}
