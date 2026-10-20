// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class OutcomeTerminalTest {

  private final OutcomeTerminal terminal = new OutcomeTerminal();

  @Test
  void completionMetadata_carriesOutcomeRestartFromAndReason() {
    final Map<String, Object> metadata =
        terminal.completionMetadata(
            Map.of("outcome", "retry", "restartFrom", "spotless", "reason", "applied"));

    assertThat(metadata)
        .containsEntry("outcome", "RETRY")
        .containsEntry("restartFrom", "spotless")
        .containsEntry("reason", "applied");
  }

  @Test
  void completionMetadata_omitsBlankOptionalFields() {
    assertThat(terminal.completionMetadata(Map.of("outcome", "SUCCESS", "reason", " ")))
        .containsOnlyKeys("outcome");
  }

  @Test
  void validateConfig_unknownOutcome_fails() {
    StepVerifier.create(terminal.validateConfig(Map.of("outcome", "MAYBE")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validateConfig_missingOutcome_fails() {
    StepVerifier.create(terminal.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void consume_completesWithAndWithoutInput() {
    final Flux<Message<?>> one = Flux.just(DefaultMessage.create(UUID.randomUUID(), "x"));

    StepVerifier.create(terminal.consume(one, Map.of("outcome", "SUCCESS"))).verifyComplete();
    StepVerifier.create(terminal.consume(Flux.empty(), Map.of("outcome", "SUCCESS")))
        .verifyComplete();
  }

  @Test
  void describe_declaresTypeAndConfig() {
    assertThat(terminal.getType()).isEqualTo("OUTCOME_TERMINAL");
    assertThat(terminal.describe().description()).isNotBlank();
    assertThat(terminal.getConfigSchema()).extracting("name").contains("outcome", "restartFrom");
  }
}
