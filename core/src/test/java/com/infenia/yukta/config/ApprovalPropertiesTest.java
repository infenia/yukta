// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests {@code yukta.control.approval.ttl}. */
@NoArgsConstructor
class ApprovalPropertiesTest {

  @Test
  void theTtlDefaultsToTenMinutesAndCanBeSet() {
    assertThat(YuktaSettings.bind(Map.of()).control().approval().ttl())
        .isEqualTo(Duration.ofMinutes(10));
    assertThat(
            YuktaSettings.bind(Map.of("yukta.control.approval.ttl", "2m"))
                .control()
                .approval()
                .ttl())
        .isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void theTtlMustBePositive() {
    assertThatThrownBy(() -> new YuktaProperties.Approval(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new YuktaProperties.Approval(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new YuktaProperties.Approval(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
