// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ApiTriggerPluginTest {

  @Test
  void testApiTrigger() {
    ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    Map<String, Object> payload = Map.of("key", "value");

    StepVerifier.create(plugin.start(Map.of()).contextWrite(ctx -> ctx.put("payload", payload)))
        .assertNext(
            message -> {
              assertEquals(payload, (Map<String, Object>) message.getPayload());
            })
        .verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("api-trigger", new ApiTriggerPlugin().getType());
  }

  @Test
  void testMetadata() {
    ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    assertEquals("Emits the payload received from an API trigger.", plugin.getDescription());
    org.junit.jupiter.api.Assertions.assertTrue(plugin.getUsagePattern().contains("REST API"));
    assertEquals(1, plugin.getOutputPorts().size());
    assertEquals("default", plugin.getOutputPorts().get(0));
    org.junit.jupiter.api.Assertions.assertTrue(plugin.getUiDesign().isPresent());
    assertEquals(140, plugin.getUiDesign().get().width());
    assertEquals(80, plugin.getUiDesign().get().height());
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.getUiDesign().get().html().contains("material-symbols-outlined"));
  }

  @Test
  void testLifecycle() {
    ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }
}
