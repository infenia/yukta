// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/** Turns node selectors (a node id, or {@code <prefix>.*}) into a rule condition. */
@UtilityClass
public class LegacySelectors {

  /** Suffix that makes a selector a prefix. */
  private static final String PREFIX_SUFFIX = ".*";

  /**
   * A condition that holds when the field {@code nodeId} is taken by one of the selectors.
   *
   * @param selectors node ids or {@code <prefix>.*}; at least one
   * @return the SpEL condition
   * @throws IllegalArgumentException if a selector is blank or has a comma
   */
  public static String condition(final List<?> selectors) {
    return selectors.stream()
        .map(LegacySelectors::one)
        .collect(Collectors.joining(" or ", "nodeId != null and (", ")"));
  }

  private static String one(final Object selector) {
    if (!(selector instanceof final String text)
        || text.isBlank()
        || text.indexOf(',') >= 0
        || PREFIX_SUFFIX.equals(text)) {
      throw new IllegalArgumentException(
          "nodes must be node ids or <prefix>.*, non-blank and without a comma");
    }
    if (text.endsWith(PREFIX_SUFFIX)) {
      final String prefix = text.substring(0, text.length() - 1);
      return "nodeId matches " + literal("^" + Pattern.quote(prefix) + ".*");
    }
    return "nodeId == " + literal(text);
  }

  /**
   * Whether a node id is taken by one of the selectors.
   *
   * @param selectors node ids or {@code <prefix>.*}; null or empty takes every node
   * @param nodeId the node id
   * @return true if it is taken
   */
  public static boolean selects(final @Nullable List<String> selectors, final String nodeId) {
    return selectors == null
        || selectors.isEmpty()
        || selectors.stream()
            .anyMatch(
                selector ->
                    selector.endsWith(PREFIX_SUFFIX)
                        ? nodeId.startsWith(selector.substring(0, selector.length() - 1))
                        : selector.equals(nodeId));
  }

  /** A SpEL string literal: single quotes are doubled. */
  /* default */ static String literal(final String text) {
    return "'" + text.replace("'", "''") + "'";
  }
}
