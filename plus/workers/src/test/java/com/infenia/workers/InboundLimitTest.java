// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The largest message a side accepts follows the messaging payload cap. */
class InboundLimitTest {

  @Test
  void testTwiceTheCapPlusOneMebibyte() {
    assertThat(WorkerTransport.inboundLimit(64L * 1024 * 1024)).isEqualTo(129 * 1024 * 1024);
  }

  @Test
  void testNoCapOrHugeCapMeansNoLimit() {
    assertThat(WorkerTransport.inboundLimit(0)).isEqualTo(Integer.MAX_VALUE);
    assertThat(WorkerTransport.inboundLimit(-1)).isEqualTo(Integer.MAX_VALUE);
    assertThat(WorkerTransport.inboundLimit(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
  }
}
