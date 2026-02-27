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
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.infenia.yukta.plugin.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class GuardProcessorTest {

  private GuardProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new GuardProcessor();
  }

  @Test
  void testSuccessfulTrueRouting() {
    final Map<String, Object> config = Map.of("condition", "payload > 100");
    final Message msg = Message.create(UUID.randomUUID(), 150);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "true".equals(m.sourcePort()) && m.payload().equals(150))
        .verifyComplete();
  }

  @Test
  void testSuccessfulFalseRouting() {
    final Map<String, Object> config = Map.of("condition", "payload > 100");
    final Message msg = Message.create(UUID.randomUUID(), 50);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "false".equals(m.sourcePort()) && m.payload().equals(50))
        .verifyComplete();
  }

  @Test
  void testMetadataAccess() {
    final Map<String, Object> config = Map.of("condition", "metadata.priority == 'HIGH'");
    final Message msg =
        new Message(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of("priority", "HIGH"),
            "data",
            java.time.Instant.now(),
            null,
            null);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "true".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testStrictModeFailure() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "strictMode", true);
    final Message msg = Message.create(UUID.randomUUID(), 150);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(e -> e.getMessage().contains("WorkflowExecutionException"))
        .verify();
  }

  @Test
  void testNonStrictModeFailure() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "strictMode", false);
    final Message msg = Message.create(UUID.randomUUID(), 150);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "false".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testErrorPortRouting() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "errorPort", "custom-error");
    final Message msg = Message.create(UUID.randomUUID(), 150);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "custom-error".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("GUARD", processor.getType());
  }
}
