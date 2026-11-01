// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

/**
 * How much damage a wrong answer can do, which decides who may give it. The constants are ordered:
 * {@link #atLeast} is "this risk or a higher one".
 *
 * @since 0.1.0-alpha.4
 */
public enum InputRisk {

  /** A wrong answer is cheap to undo. */
  LOW,

  /** A wrong answer costs time or money but can be undone. */
  MEDIUM,

  /** A wrong answer is hard to undo; only a person may give it. */
  HIGH;

  /**
   * Whether this risk is the other one or higher.
   *
   * @param other the risk to compare with
   * @return true if this is {@code other} or riskier
   */
  public boolean atLeast(final InputRisk other) {
    return compareTo(other) >= 0;
  }
}
