// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

/** Calls a deprecated constructor; the self-test expects the rule to object. */
@SuppressWarnings("PMD")
public final class BadDeprecated {
  private BadDeprecated() {}

  /** Uses deprecated API. */
  @SuppressWarnings("deprecation")
  public static java.util.Date old() {
    return new java.util.Date(2020, 1, 1);
  }
}
