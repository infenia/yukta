// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Api trigger plugin test. */
@NoArgsConstructor
class ApiTriggerPluginTest {

  @Test
  void testApiTrigger() {
    final ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    final Map<String, Object> payload = Map.of("key", "value");

    StepVerifier.create(plugin.start(Map.of()).contextWrite(ctx -> ctx.put("payload", payload)))
        .assertNext(
            message -> {
              assertEquals(payload, (Map<String, Object>) message.payload(), "Test api trigger");
            })
        .verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("api-trigger", new ApiTriggerPlugin().getType(), "Test get type");
  }

  @Test
  void testMetadata() {
    final ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    assertEquals(
        "Emits the payload received from an API trigger.",
        plugin.describe().description(),
        "Test metadata");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().usagePattern().contains("REST API"), "Test metadata (2)");
    assertEquals(1, plugin.getOutputPorts().size(), "Test metadata (3)");
    assertEquals("default", plugin.getOutputPorts().get(0), "Test metadata (4)");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().uiDesign().isPresent(), "Test metadata (5)");
    assertEquals(140, plugin.describe().uiDesign().get().width(), "Test metadata (6)");
    assertEquals(80, plugin.describe().uiDesign().get().height(), "Test metadata (7)");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().uiDesign().get().html().contains("material-symbols-outlined"),
        "Test metadata (8)");
  }

  @Test
  void testLifecycle() {
    final ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }
}
