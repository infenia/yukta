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
package com.infenia.jagratha.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ApiTriggerPluginTest {

  @Test
  void testApiTrigger() {
    ApiTriggerPlugin plugin = new ApiTriggerPlugin();
    Map<String, Object> payload = Map.of("key", "value");

    StepVerifier.create(plugin.start(Map.of(), payload))
        .assertNext(
            message -> {
              assertEquals(payload, message.payload());
            })
        .verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("api-trigger", new ApiTriggerPlugin().getType());
  }
}
