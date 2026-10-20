// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package com.infenia.yukta.util;

/** Canary: SpotBugs ES_COMPARING_STRINGS_WITH_EQ. */
public final class CanarySpotBugs {
  private CanarySpotBugs() {}

  /** Compares strings by reference on purpose. */
  public static boolean same(String a) {
    return a == new String("x");
  }
}
