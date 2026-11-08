// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

/** Sleeps; the self-test expects the rule to object. */
public final class BadSleep {
  private BadSleep() {}

  /** Parks the thread. */
  public static void nap() throws InterruptedException {
    Thread.sleep(1);
  }
}
