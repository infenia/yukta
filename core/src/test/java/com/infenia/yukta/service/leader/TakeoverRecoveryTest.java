// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.service.log.ExecutionRecovery;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class TakeoverRecoveryTest {

  private final LeaderElection election = mock(LeaderElection.class);
  private final ExecutionRecovery recovery = mock(ExecutionRecovery.class);
  private final ExecutionResumer resumer = mock(ExecutionResumer.class);

  @Test
  void autoResumeFromSettings_registersTheResumeHookOnRecovery() {
    new TakeoverRecovery(
        election,
        recovery,
        resumer,
        YuktaSettings.bind(Map.of("yukta.restart.auto-resume", "true")));

    verify(recovery).onRecovered(any());
  }

  @Test
  void defaultSettings_doNotResumeOnTheirOwn() {
    new TakeoverRecovery(election, recovery, resumer, YuktaSettings.bind(Map.of()));

    verify(recovery, never()).onRecovered(any());
  }
}
