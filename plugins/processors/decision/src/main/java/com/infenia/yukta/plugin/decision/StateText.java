// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import lombok.experimental.UtilityClass;

/** Keeps the text a decision model is asked about within the limits of its request. */
@UtilityClass
public class StateText {

  /** Most characters sent; System One refuses requests above 64 KiB. */
  public static final int MAX = 15_000;

  /**
   * Cuts the text to {@link #MAX} characters without splitting a supplementary character.
   *
   * @param text the text
   * @return the text, or its first {@link #MAX} characters (one fewer if the cut would fall inside
   *     a surrogate pair)
   */
  public static String truncate(final String text) {
    String result = text;
    if (text.length() > MAX) {
      final int end = Character.isHighSurrogate(text.charAt(MAX - 1)) ? MAX - 1 : MAX;
      result = text.substring(0, end);
    }
    return result;
  }
}
