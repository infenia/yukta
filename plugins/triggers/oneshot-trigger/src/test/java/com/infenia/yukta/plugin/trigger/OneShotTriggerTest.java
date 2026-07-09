// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.test.StepVerifier;

/** Tests for OneShotTrigger. */
@MockitoSettings
@NoArgsConstructor
class OneShotTriggerTest {

  /** The trigger instance under test. */
  private final OneShotTrigger trigger = new OneShotTrigger();

  @Test
  void type_returnsOneShot() {
    assertThat(trigger.getType()).isEqualTo("ONE_SHOT");
  }

  @Test
  void start_emitsExactlyOneMessage() {
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(message -> assertThat(message.getPayload()).isNotNull())
        .verifyComplete();
  }

  @Test
  void start_emittedMessageHasEmptyMapPayload() {
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(message -> assertThat(message.getPayload()).isEqualTo(Map.of()))
        .verifyComplete();
  }

  @Test
  void start_emittedMessageHasNonNullTraceId() {
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(
            message -> {
              final var traceId = message.getTraceId();
              assertThat(traceId).isNotNull();
            })
        .verifyComplete();
  }

  @Test
  void validateConfig_completesWithoutError() {
    StepVerifier.create(trigger.validateConfig(Map.of())).verifyComplete();
  }

  @Test
  void uiDesign_isPresentAndCorrectDimensions() {
    final var design = trigger.getUiDesign();
    assertThat(design).isPresent();
    assertThat(design.get().width()).isEqualTo(140);
    assertThat(design.get().height()).isEqualTo(80);
  }

  @Test
  void description_returnsCorrectDescription() {
    assertThat(trigger.getDescription())
        .isEqualTo(
            "Fires a workflow with no input. Emits a single empty message to start execution.");
  }

  @Test
  void usagePattern_returnsCorrectUsagePattern() {
    assertThat(trigger.getUsagePattern())
        .isEqualTo(
            "No configuration required. Call the workflow trigger endpoint to start execution.");
  }

  @Test
  void constructor_instantiatesSuccessfully() {
    final OneShotTrigger newTrigger = new OneShotTrigger();
    assertThat(newTrigger).isNotNull();
    assertThat(newTrigger.getType()).isEqualTo("ONE_SHOT");
  }
}
