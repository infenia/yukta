// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.Locale;
import lombok.experimental.UtilityClass;

/** Keeps the text a decision model is asked about within the limits of its request. */
@UtilityClass
public class StateText {

  /** Most characters sent; System One refuses requests above 64 KiB. */
  public static final int MAX = 15_000;

  /** Room kept for the {@code ... [N characters omitted] ...} note of {@link Keep#HEAD_TAIL}. */
  private static final int NOTE_RESERVE = 48;

  /**
   * Below this limit the note would take most of the text, so {@link Keep#HEAD_TAIL} cuts the head.
   */
  private static final int MIN_HEAD_TAIL = 100;

  /** Which part of a text that is too long is kept. */
  public enum Keep {
    /** The first characters (where a document states what it is about). */
    HEAD,
    /** The last characters (where build and test output states what failed). */
    TAIL,
    /** The first and the last half, with a note where the middle was left out. */
    HEAD_TAIL;

    /**
     * Reads the config spelling: {@code head}, {@code tail} or {@code headTail}.
     *
     * @param value the text, case-insensitive
     * @return the choice
     * @throws IllegalArgumentException if it is none of them
     */
    public static Keep parse(final String value) {
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "head" -> HEAD;
        case "tail" -> TAIL;
        case "headtail" -> HEAD_TAIL;
        default -> throw new IllegalArgumentException("stateKeep must be head, tail or headTail");
      };
    }
  }

  /**
   * Cuts the text to {@link #MAX} characters, keeping the first ones.
   *
   * @param text the text
   * @return the text, or its first {@link #MAX} characters (one fewer if the cut would fall inside
   *     a surrogate pair)
   */
  public static String truncate(final String text) {
    return truncate(text, MAX, Keep.HEAD);
  }

  /**
   * Cuts the text to at most {@code max} characters without splitting a supplementary character.
   *
   * @param text the text
   * @param max the most characters kept (at least 1)
   * @param keep which part to keep
   * @return the text if it fits, else the kept part (for {@link Keep#HEAD_TAIL} with a note of how
   *     many characters were left out)
   */
  public static String truncate(final String text, final int max, final Keep keep) {
    final int length = text.length();
    String result = text;
    if (length > max) {
      if (keep == Keep.HEAD_TAIL && max >= MIN_HEAD_TAIL) {
        result = headTail(text, max);
      } else if (keep == Keep.TAIL) {
        result = tail(text, max);
      } else {
        result = head(text, max);
      }
    }
    return result;
  }

  private static String head(final String text, final int max) {
    return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
  }

  private static String tail(final String text, final int max) {
    final int start = text.length() - max;
    return text.substring(Character.isLowSurrogate(text.charAt(start)) ? start + 1 : start);
  }

  private static String headTail(final String text, final int max) {
    final int budget = max - NOTE_RESERVE;
    final int headLength = budget / 2;
    final String head = head(text, headLength);
    final String tail = tail(text, budget - headLength);
    final int omitted = text.length() - head.length() - tail.length();
    return head + "\n... [" + omitted + " characters omitted] ...\n" + tail;
  }
}
