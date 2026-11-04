// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import lombok.experimental.UtilityClass;

/**
 * Builds URL-friendly slugs from free text.
 *
 * <p>Only ASCII letters and digits are kept; they are lower-cased and joined by single hyphens.
 * Every other character is treated as a separator, so runs of separators collapse to one hyphen and
 * no hyphen leads or trails the result.
 */
@UtilityClass
public class TextSlug {

  /**
   * Converts text to a slug of lower-case ASCII letters and digits joined by single hyphens.
   *
   * @param text the text to convert, may be null
   * @return the slug; empty when the text is null or holds no ASCII letters or digits
   */
  public String toSlug(final String text) {
    if (text == null) {
      return "";
    }
    final StringBuilder slug = new StringBuilder(text.length());
    boolean pendingSeparator = false;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
        appendPending(slug, pendingSeparator);
        slug.append(c);
        pendingSeparator = false;
      } else if (c >= 'A' && c <= 'Z') {
        appendPending(slug, pendingSeparator);
        slug.append((char) (c + 32));
        pendingSeparator = false;
      } else {
        pendingSeparator = true;
      }
    }
    return slug.toString();
  }

  private static void appendPending(final StringBuilder slug, final boolean pendingSeparator) {
    if (pendingSeparator && slug.length() > 0) {
      slug.append('-');
    }
  }
}
