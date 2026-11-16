// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import java.util.Map;
import java.util.Set;

/**
 * What one person wants to be told. They are kept on the server so the browser and {@code yukta
 * notify} agree.
 *
 * @param recipient the key name, or {@link Recipients#LOCAL}
 * @param levels the level per category; a category without an entry keeps its default
 * @param mutedSessions sessions whose notifications are not raised for this person
 */
public record NotificationPreferences(
    String recipient, Map<String, Level> levels, Set<String> mutedSessions) {

  /** The category of input requests, which can never be turned off. */
  private static final String INPUT = "input";

  /** How far a notification of a category reaches. */
  public enum Level {

    /** Not at all. */
    OFF,

    /** In the inbox. */
    INBOX,

    /** In the inbox and as a desktop notification. */
    DESKTOP
  }

  /** Copies the collections and refuses to turn input requests off. */
  public NotificationPreferences {
    levels = Map.copyOf(levels);
    mutedSessions = Set.copyOf(mutedSessions);
    if (levels.get(INPUT) == Level.OFF) {
      throw new IllegalArgumentException("The category 'input' cannot be turned off");
    }
  }

  /**
   * The preferences of someone who set nothing.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @return empty preferences
   */
  public static NotificationPreferences defaults(final String recipient) {
    return new NotificationPreferences(recipient, Map.of(), Set.of());
  }
}
