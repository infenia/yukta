// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ShortMethodName"})
class SupervisorDecisionTerminalTest {

  private final SupervisorDecisionTerminal terminal = new SupervisorDecisionTerminal();

  private static Message<?> of(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void itDescribesItselfAndDefaultsToSuccess() {
    assertThat(terminal.getType()).isEqualTo("SUPERVISOR_DECISION");
    assertThat(terminal.describe().description()).isNotBlank();
    assertThat(terminal.getConfigSchema()).isNotEmpty();
    assertThat(terminal.completionMetadata(Map.of())).containsEntry("outcome", "SUCCESS");
    assertThat(terminal.completionMetadata(Map.of("outcome", "escalate", "reason", "unsure")))
        .containsEntry("outcome", "ESCALATE")
        .containsEntry("reason", "unsure");
    StepVerifier.create(terminal.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(terminal.validateConfig(Map.of("outcome", "maybe")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validCommandsPassAndOtherPayloadsAreLeftAlone() {
    StepVerifier.create(
            terminal.consume(
                Flux.just(
                    of(
                        Map.of(
                            "commands",
                            List.of(Map.of("type", "PauseWorkflow", "executionId", "e1")))),
                    of("plain text"),
                    of(Map.of("other", 1))),
                Map.of()))
        .verifyComplete();
  }

  @Test
  void commandTheContractCannotReadFailsTheRun() {
    StepVerifier.create(
            terminal.consume(
                Flux.just(
                    of(
                        Map.of(
                            "commands",
                            List.of(
                                Map.of("type", "PauseWorkflow", "executionId", "e1"),
                                Map.of("type", "Nope", "executionId", "e1"))))),
                Map.of()))
        .expectErrorSatisfies(
            error -> assertThat(error).hasMessageContaining("1 of 2 commands are not valid"))
        .verify();
  }

  @Test
  void moreThanTenCommandsFailTheRun() {
    final List<Object> many = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      many.add(Map.of("type", "PauseWorkflow", "executionId", "e" + i));
    }
    assertThatThrownBy(
            () -> terminal.consume(Flux.just(of(Map.of("commands", many))), Map.of()).block())
        .hasMessageContaining("at most 10");
  }
}
