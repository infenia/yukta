// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import lombok.experimental.UtilityClass;

/** Waits for the clock instead of guessing how long a sleep must be. */
@UtilityClass
public class Timing {

  /**
   * Returns once the millisecond clock has moved on, so two timestamps taken before and after this
   * call differ. For code that stamps a version with the current time: a fixed {@code
   * Thread.sleep(2)} is either too short on a coarse clock or wastes time.
   */
  public static void nextMillisecond() {
    final long start = System.currentTimeMillis();
    while (System.currentTimeMillis() == start) {
      Thread.onSpinWait();
    }
  }
}
