// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Webhook trigger plugin test. */
@NoArgsConstructor
class WebhookTriggerPluginTest {

  private final WebhookTriggerPlugin plugin = new WebhookTriggerPlugin();

  @Test
  void emitsThePayloadTheRunStartedWith() {
    final Map<String, Object> payload = Map.of("body", "{}", "headers", Map.of());

    StepVerifier.create(plugin.start(Map.of()).contextWrite(ctx -> ctx.put("payload", payload)))
        .assertNext(message -> assertEquals(payload, message.payload(), "payload"))
        .verifyComplete();
  }

  @Test
  void withoutPayloadEmitsAnEmptyOne() {
    StepVerifier.create(plugin.start(Map.of()))
        .assertNext(message -> assertEquals(Map.of(), message.payload(), "payload"))
        .verifyComplete();
  }

  @Test
  void validateConfig_requiresPathAndSecret() {
    StepVerifier.create(plugin.validateConfig(Map.of("path", "push", "secret", "decrypted:gh")))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of("secret", "decrypted:gh")))
        .expectErrorMessage("path is required")
        .verify();
    StepVerifier.create(plugin.validateConfig(Map.of("path", " ", "secret", "decrypted:gh")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(Map.of("path", "push")))
        .expectErrorMessage("secret is required")
        .verify();
  }

  @Test
  void describesItself() {
    assertEquals("WEBHOOK_TRIGGER", plugin.getType(), "type");
    assertEquals(6, plugin.getConfigSchema().size(), "schema");
    assertTrue(plugin.describe().description().contains("webhook"), "description");
    assertTrue(plugin.describe().uiDesign().isPresent(), "design");
  }
}
