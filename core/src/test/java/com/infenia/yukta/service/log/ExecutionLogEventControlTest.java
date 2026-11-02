// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionLogEventControlTest {

  private static final String PAUSE = "Pause";

  @Test
  void control_keepsCommandActorAndReasonAsFields() {
    final ExecutionLogEvent rejected =
        ExecutionLogEvent.control(false, "e1", "Pause [by key:ops]", PAUSE, "key:ops", "busy");

    assertThat(rejected.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
    assertThat(rejected.status()).isEqualTo("Pause [by key:ops]: busy");
    assertThat(rejected.payload())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                ExecutionLogEvent.COMMAND_KEY, PAUSE,
                ExecutionLogEvent.ACTOR_KEY, "key:ops",
                ExecutionLogEvent.REASON_KEY, "busy"));
  }

  @Test
  void control_leavesOutActorAndReasonWhenThereAreNone() {
    final ExecutionLogEvent noActor =
        ExecutionLogEvent.control(false, "e1", PAUSE, PAUSE, null, null);
    final ExecutionLogEvent applied =
        ExecutionLogEvent.control(true, "e1", PAUSE, PAUSE, null, "ignored");

    assertThat(noActor.payload()).containsOnlyKeys(ExecutionLogEvent.COMMAND_KEY);
    assertThat(applied.payload()).containsOnlyKeys(ExecutionLogEvent.COMMAND_KEY);
  }
}
