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
package com.infenia.jagratha.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.infenia.jagratha.plugin.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FilterProcessorTest {

  private FilterProcessor processor;

  @BeforeEach
  void setUp() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    processor =
        new FilterProcessor(
            beanFactory.getBeanProvider(com.infenia.jagratha.plugin.PluginMetricsReporter.class));
  }

  @Test
  void testSpelMatch() {
    final Map<String, Object> config = Map.of("condition", "payload.status == 'ACTIVE'");
    final Message msg = Message.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).expectNext(msg).verifyComplete();
  }

  @Test
  void testSpelNoMatch() {
    final Map<String, Object> config = Map.of("condition", "payload.status == 'ACTIVE'");
    final Message msg = Message.create(UUID.randomUUID(), Map.of("status", "INACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testSimpleEngineMatch() {
    final Map<String, Object> config =
        Map.of(
            "condition", "payload.status == 'ACTIVE'",
            "engine", "SIMPLE");
    final Message msg = Message.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).expectNext(msg).verifyComplete();
  }

  @Test
  void testDiscardPort() {
    final Map<String, Object> config =
        Map.of(
            "condition", "payload.status == 'ACTIVE'",
            "discardPort", "dead-letter");
    final Message msg = Message.create(UUID.randomUUID(), Map.of("status", "INACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "dead-letter".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "strictMode", true);
    final Message msg = Message.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(e -> e instanceof com.infenia.jagratha.plugin.FilterEvaluationException)
        .verify();
  }

  @Test
  void testNonStrictModeError() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "strictMode", false);
    final Message msg = Message.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("FILTER", processor.getType());
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of("condition", "true"))).verifyComplete();

    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of("condition", "true", "engine", "INVALID")))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of("condition", "true", "engine", "REGO")))
        .expectErrorMatches(e -> e.getMessage().contains("reserved"))
        .verify();
  }
}
