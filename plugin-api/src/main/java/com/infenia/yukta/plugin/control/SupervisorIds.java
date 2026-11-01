// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The naming rule for supervisor ids: profile ids, binding ids and the {@link
 * ExecutionSupervisor#id()} of a supervisor registered as a bean.
 *
 * <p>An id matches {@value #RULE}. The colon and every whitespace or control character are
 * excluded, so an id can never look like an actor of another kind, such as {@code key:<name>} or
 * {@code token:<tier>}, in the audit log, in metric tags or in the UI.
 */
@UtilityClass
public class SupervisorIds {

  /** The rule an id must match, as a regular expression. */
  public static final String RULE = "[A-Za-z0-9._-]{1,64}";

  /** The compiled rule. */
  private static final Pattern PATTERN = Pattern.compile(RULE);

  /**
   * Whether an id follows the rule.
   *
   * @param id the id, may be null
   * @return true if it is not null and matches {@value #RULE}
   */
  public static boolean isValid(final String id) {
    return id != null && PATTERN.matcher(id).matches();
  }

  /**
   * Checks an id.
   *
   * @param id the id
   * @param what what the id names, for the message (for example {@code "profile id"})
   * @return the id, unchanged
   * @throws IllegalArgumentException if the id is null or breaks the rule; the message names the
   *     rule
   */
  public static String validate(final String id, final String what) {
    if (!isValid(id)) {
      throw new IllegalArgumentException(
          "Invalid supervisor "
              + what
              + " '"
              + printable(id)
              + "': use "
              + RULE
              + " (letters,"
              + " digits, '.', '_' and '-', 1 to 64 characters; no ':' or whitespace)");
    }
    return id;
  }

  /**
   * Checks an id, naming it only as a supervisor id.
   *
   * @param id the id
   * @return the id, unchanged
   * @throws IllegalArgumentException if the id is null or breaks the rule
   */
  public static String validate(final String id) {
    return validate(id, "id");
  }

  /** The id for a message: control characters shown escaped and a long id cut short. */
  private static String printable(final String id) {
    if (id == null) {
      return "null";
    }
    final String cut = id.length() > 80 ? id.substring(0, 80) + "..." : id;
    final StringBuilder out = new StringBuilder(cut.length());
    for (final char c : cut.toCharArray()) {
      if (Character.isISOControl(c)) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
