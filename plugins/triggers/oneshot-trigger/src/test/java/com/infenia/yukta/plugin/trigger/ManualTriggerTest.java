/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ManualTriggerTest {

  private final ManualTrigger trigger = new ManualTrigger();

  @Test
  void getType_returnsManual() {
    assertEquals("MANUAL", trigger.getType());
  }

  @Test
  void start_emitsExactlyOneMessage() {
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(message -> assertNotNull(message.getPayload()))
        .verifyComplete();
  }

  @Test
  void start_emittedMessageHasEmptyMapPayload() {
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(message -> assertEquals(Map.of(), message.getPayload()))
        .verifyComplete();
  }

  @Test
  void start_emittedMessageHasNonNullTraceId() {
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(message -> assertNotNull(message.getTraceId()))
        .verifyComplete();
  }

  @Test
  void validateConfig_completesWithoutError() {
    StepVerifier.create(trigger.validateConfig(Map.of())).verifyComplete();
  }

  @Test
  void getUiDesign_isPresentAndCorrectDimensions() {
    var design = trigger.getUiDesign();
    assertTrue(design.isPresent());
    assertEquals(140, design.get().width());
    assertEquals(80, design.get().height());
  }

  @Test
  void getDescription_returnsCorrectDescription() {
    assertEquals(
        "Fires a workflow with no input. Emits a single empty message to start execution.",
        trigger.getDescription());
  }

  @Test
  void getUsagePattern_returnsCorrectUsagePattern() {
    assertEquals(
        "No configuration required. Call the workflow trigger endpoint to start execution.",
        trigger.getUsagePattern());
  }

  @Test
  void constructor_instantiatesSuccessfully() {
    ManualTrigger newTrigger = new ManualTrigger();
    assertNotNull(newTrigger);
    assertEquals("MANUAL", newTrigger.getType());
  }
}
