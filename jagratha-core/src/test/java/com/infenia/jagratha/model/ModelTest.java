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
package com.infenia.jagratha.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTest {

  @Test
  void testApiResponseSuccess() {
    ApiResponse<String> response = ApiResponse.success(200, "Success", "data");
    assertNotNull(response.timestamp());
    assertEquals(200, response.status());
    assertEquals("Success", response.message());
    assertEquals("data", response.data());
  }

  @Test
  void testApiResponseError() {
    ApiResponse.FieldError fieldError = new ApiResponse.FieldError("field", "error");
    ApiResponse<Void> response =
        ApiResponse.error(400, "Bad Request", "Validation failed", "/path", List.of(fieldError));
    assertNotNull(response.timestamp());
    assertEquals(400, response.status());
    assertEquals("Bad Request", response.error());
    assertEquals("Validation failed", response.message());
    assertEquals("/path", response.path());
    assertEquals(1, response.errors().size());
  }

  @Test
  void testWorkflowDefinitionImmutability() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", Map.of("k", "v"));
    WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("n1", "n2");
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(node), List.of(edge));

    assertNotNull(def.nodes());
    assertNotNull(def.edges());
    assertEquals(1, def.nodes().size());
  }
}
