// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class ThrottleWindowsTest {

  private static final long PERIOD = 1_000;

  @Test
  void keyWithoutAdmissions_fits() {
    final ThrottleWindows windows = new ThrottleWindows();

    assertThat(windows.fits("k", 1, 0)).isTrue();
    assertThat(windows.waitNanos("k", 1, 0)).isZero();
  }

  @Test
  void keyAtItsLimit_waitsUntilTheOldestNeededAdmissionLeavesThePeriod() {
    final ThrottleWindows windows = new ThrottleWindows();
    windows.take("k", PERIOD, 100);
    windows.take("k", PERIOD, 400);

    assertThat(windows.fits("k", 2, 500)).isFalse();
    assertThat(windows.waitNanos("k", 2, 500)).isEqualTo(600);
    // with a limit of one, the newest admission has to leave
    assertThat(windows.waitNanos("k", 1, 500)).isEqualTo(900);
    assertThat(windows.fits("k", 2, 1_100)).isTrue();
    assertThat(windows.fits("other", 1, 500)).isTrue();
  }

  @Test
  void windowWhoseAdmissionsAllLeftThePeriod_fitsAgain() {
    final ThrottleWindows windows = new ThrottleWindows();
    windows.take("k", PERIOD, 0);

    assertThat(windows.waitNanos("k", 1, 5_000)).isZero();
    assertThat(windows.fits("k", 1, 5_000)).isTrue();
  }

  @Test
  void windowsThatHaveEmptied_areSweptAfterManyAdmissions() {
    final ThrottleWindows windows = new ThrottleWindows();
    windows.take("idle", PERIOD, 0);
    for (int i = 0; i < 1_023; i++) {
      windows.take("busy", PERIOD, 10_000);
    }

    assertThat(windows.size()).isEqualTo(1);
  }
}
