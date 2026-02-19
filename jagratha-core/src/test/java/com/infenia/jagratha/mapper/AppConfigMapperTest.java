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
package com.infenia.jagratha.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.PluginRegistration;
import com.infenia.jagratha.model.WorkflowConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppConfigMapperTest {

  @Autowired private AppConfigMapper mapper;

  @Test
  void testMapping() {
    List<PluginRegistration> plugins = List.of(new PluginRegistration("p1", Map.of("k", "v")));
    ConfigRequest request =
        new ConfigRequest(
            "sess-1", "/path", plugins, List.of(new WorkflowConfig("w1", null, null)));

    AppConfigData data = mapper.toData(request);

    assertNotNull(data);
    assertEquals("sess-1", data.sessionId());
    assertEquals("/path", data.projectPath());
    assertEquals(1, data.plugins().size());
    assertEquals("p1", data.plugins().get(0).name());
    assertEquals(Map.of("k", "v"), data.plugins().get(0).pluginConfig());
    assertEquals(1, data.workflows().size());
  }
}
