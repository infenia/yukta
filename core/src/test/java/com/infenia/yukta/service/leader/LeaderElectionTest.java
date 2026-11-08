// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class LeaderElectionTest {

  @Test
  void singleServer_isAlwaysLeader() {
    final LeaderElection single = new SingleServerLeader();

    assertThat(single.isLeader()).isTrue();
    assertThat(single.recoversOnTakeover()).isFalse();
    single.onTakeover(() -> {});
    single.onLoss(() -> {});
    assertThat(new NotLeaderException().getMessage()).contains("standby");
  }
}
