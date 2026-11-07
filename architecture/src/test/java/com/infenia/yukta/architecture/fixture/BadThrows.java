// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

/** Throws a generic exception; the self-test expects the rule to object. */
@SuppressWarnings("PMD")
public final class BadThrows {
  private BadThrows() {}

  /** Fails. */
  public static void fail() {
    throw new RuntimeException("generic");
  }
}
