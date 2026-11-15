// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.testsupport.YuktaSettings;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The settings under {@code yukta.agents}. */
@NoArgsConstructor
class AgentSettingsTest {

  @Test
  void theDefaultsAreTheOnesOfThePlan() {
    final YuktaProperties.Agents agents = YuktaSettings.bind(Map.of()).agents();

    assertThat(agents.enabled()).isTrue();
    assertThat(agents.retention()).isEqualTo(Duration.ofDays(7));
    assertThat(agents.maxConversations()).isEqualTo(500);
    assertThat(agents.maxEventsPerConversation()).isEqualTo(5000);
    assertThat(agents.maxMessageBytes()).isEqualTo(65_536);
    assertThat(agents.maxToolBytes()).isEqualTo(4096);
    assertThat(agents.terminalRingBytes()).isEqualTo(262_144);
    assertThat(agents.messagesPerMinute()).isEqualTo(30);
    assertThat(agents.pty().enabled()).isFalse();
    assertThat(agents.pty().host()).isEqualTo("yukta-cli");
    assertThat(agents.pty().cols()).isEqualTo(120);
    assertThat(agents.pty().rows()).isEqualTo(40);
  }

  @Test
  void theSettingsCanBeChanged() {
    final YuktaProperties.Agents agents =
        YuktaSettings.bind(
                Map.of(
                    "yukta.agents.enabled", "false",
                    "yukta.agents.retention", "1d",
                    "yukta.agents.pty.enabled", "true",
                    "yukta.agents.pty.cols", "200"))
            .agents();

    assertThat(agents.enabled()).isFalse();
    assertThat(agents.retention()).isEqualTo(Duration.ofDays(1));
    assertThat(agents.pty().enabled()).isTrue();
    assertThat(agents.pty().cols()).isEqualTo(200);
  }

  @Test
  void aNonPositiveRetentionStopsStartUp() {
    assertThatThrownBy(() -> YuktaSettings.bind(Map.of("yukta.agents.retention", "0s")))
        .hasStackTraceContaining("yukta.agents.retention must be positive");
  }

  @Test
  void aBoundBelowOneOrATerminalTooBigIsAViolation() {
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    final YuktaProperties.Agents bad =
        new YuktaProperties.Agents(
            true,
            Duration.ofDays(1),
            0,
            0,
            0,
            0,
            0,
            0,
            new YuktaProperties.Agents.Pty(false, " ", 5000, 0));

    assertThat(validator.validate(bad))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactlyInAnyOrder(
            "maxConversations",
            "maxEventsPerConversation",
            "maxMessageBytes",
            "maxToolBytes",
            "terminalRingBytes",
            "messagesPerMinute",
            "pty.host",
            "pty.cols",
            "pty.rows");
    assertThat(validator.validate(YuktaSettings.bind(Map.of()).agents())).isEmpty();
  }
}
