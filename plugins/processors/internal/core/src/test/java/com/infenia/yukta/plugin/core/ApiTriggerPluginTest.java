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
    assertEquals(120, plugin.getUiDesign().get().width());
    assertEquals(60, plugin.getUiDesign().get().height());
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
