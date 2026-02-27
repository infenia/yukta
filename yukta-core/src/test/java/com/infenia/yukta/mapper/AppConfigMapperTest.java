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
package com.infenia.yukta.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.yukta.model.AppConfigData;
import com.infenia.yukta.model.ConfigRequest;
import com.infenia.yukta.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = AppConfigMapperImpl.class)
class AppConfigMapperTest {

  @Autowired private AppConfigMapper mapper;

  @Test
  void testMapping() {
    WorkflowDefinition workflow = new WorkflowDefinition("desc-w", List.of(), List.of());
    Map<String, String> tags = Map.of("key", "value");
    java.util.Map<String, WorkflowDefinition> workflows = java.util.Map.of("w1", workflow);
    ConfigRequest request =
        new ConfigRequest("sess-1", "desc-s", "initiator-1", tags, "/path", workflows);
    AppConfigData data = mapper.toData(request);

    assertNotNull(data);
    assertEquals("sess-1", data.sessionId());
    assertEquals("desc-s", data.description());
    assertEquals("initiator-1", data.initiator());
    assertEquals(tags, data.tags());
    assertEquals("/path", data.projectPath());
    assertEquals(workflow, data.workflows().get("w1"));
  }
}
