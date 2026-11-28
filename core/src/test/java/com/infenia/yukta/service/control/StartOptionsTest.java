// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.runtime.ExecutionScheduler;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartOptionsTest {

  @Test
  void externalPayloadCannotSetStartOptionsOnlyTypedOptionsCan() {
    final var clean =
        StartOptions.withInitiator(
            Map.of(ExecutionScheduler.PRIORITY_KEY, 99, StartOptions.VERSION_KEY, "old"), "k");
    assertThat(clean).doesNotContainKeys(ExecutionScheduler.PRIORITY_KEY, StartOptions.VERSION_KEY);

    assertThat(new StartOptions(5, " v1 ").into(clean))
        .containsEntry(ExecutionScheduler.PRIORITY_KEY, 5)
        .containsEntry(StartOptions.VERSION_KEY, "v1");
    assertThat(new StartOptions(null, " ").isEmpty()).isTrue();
  }

  @Test
  void startOptionsWriteOnlyWhatIsSet() {
    assertThat(new StartOptions(3, null).into(Map.of()))
        .containsOnlyKeys(ExecutionScheduler.PRIORITY_KEY);
    assertThat(new StartOptions(null, "v2").into(Map.of()))
        .containsOnlyKeys(StartOptions.VERSION_KEY);
    assertThat(new StartOptions(null, " ").into(Map.of())).isEmpty();
    assertThat(new StartOptions(0, null).isEmpty()).isFalse();
    assertThat(new StartOptions(null, "v").isEmpty()).isFalse();
    assertThat(new StartOptions(null, null).isEmpty()).isTrue();
  }
}
