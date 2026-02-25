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
import com.infenia.jagratha.plugin.NoMatchingBranchException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class BranchProcessorTest {

  private BranchProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new BranchProcessor();
  }

  @Test
  void testSelectKeyMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.userType",
            "cases",
                Map.of(
                    "PREMIUM", "premium_port",
                    "GUEST", "guest_port"));

    final Message msg1 = Message.create(UUID.randomUUID(), Map.of("userType", "PREMIUM"));
    final Message msg2 = Message.create(UUID.randomUUID(), Map.of("userType", "GUEST"));

    StepVerifier.create(processor.process(Flux.just(msg1), config))
        .expectNextMatches(m -> "premium_port".equals(m.sourcePort()))
        .verifyComplete();

    StepVerifier.create(processor.process(Flux.just(msg2), config))
        .expectNextMatches(m -> "guest_port".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testSelectKeyTypeCoercion() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.code",
            "cases",
                Map.of(
                    "1", "port_1",
                    "2", "port_2"));

    final Message msg = Message.create(UUID.randomUUID(), Map.of("code", 1));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "port_1".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testExpressionModeSingleMatch() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.score > 90", "high_score");
    cases.put("payload.score <= 90", "low_score");

    final Map<String, Object> config = Map.of("mode", "EXPRESSION", "cases", cases);

    final Message msg = Message.create(UUID.randomUUID(), Map.of("score", 95));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "high_score".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testExpressionModeMultipleMatches() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.score > 50", "pass");
    cases.put("payload.score > 90", "excellent");

    final Map<String, Object> config =
        Map.of("mode", "EXPRESSION", "allowMultipleMatches", true, "cases", cases);

    final Message msg = Message.create(UUID.randomUUID(), Map.of("score", 95));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "pass".equals(m.sourcePort()))
        .expectNextMatches(m -> "excellent".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testDefaultPort() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.userType",
            "cases", Map.of("ADMIN", "admin_port"),
            "defaultPort", "default_port");

    final Message msg = Message.create(UUID.randomUUID(), Map.of("userType", "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "default_port".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SELECT_KEY",
            "selector",
            "payload.userType",
            "cases",
            Map.of("ADMIN", "admin_port"),
            "strictMode",
            true);

    final Message msg = Message.create(UUID.randomUUID(), Map.of("userType", "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testNonStrictModeEmpty() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SELECT_KEY",
            "selector",
            "payload.userType",
            "cases",
            Map.of("ADMIN", "admin_port"),
            "strictMode",
            false);

    final Message msg = Message.create(UUID.randomUUID(), Map.of("userType", "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testValidateConfig() {
    final Map<String, Object> validConfig =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.id",
            "cases", Map.of("1", "port1"));
    StepVerifier.create(processor.validateConfig(validConfig)).verifyComplete();

    final Map<String, Object> invalidMode = Map.of("mode", "INVALID", "cases", Map.of());
    StepVerifier.create(processor.validateConfig(invalidMode)).expectError().verify();

    final Map<String, Object> missingSelector = Map.of("mode", "SELECT_KEY", "cases", Map.of());
    StepVerifier.create(processor.validateConfig(missingSelector)).expectError().verify();

    final Map<String, Object> missingCases = Map.of("mode", "EXPRESSION");
    StepVerifier.create(processor.validateConfig(missingCases)).expectError().verify();
  }

  @Test
  void testType() {
    assertEquals("branch", processor.getType());
  }
}
