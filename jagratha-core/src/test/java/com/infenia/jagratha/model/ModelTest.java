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

import org.junit.jupiter.api.Test;

class ModelTest {

  @Test
  void testFileRequest() {
    FileRequest request = new FileRequest("path", "session-1", "content");
    assertEquals("path", request.path());
    assertEquals("session-1", request.sessionId());
    assertEquals("content", request.content());
    assertNotNull(request.toString());
  }

  @Test
  void testTaskResponse() {
    TaskResponse response = new TaskResponse("SUCCESS", "output");
    assertEquals("SUCCESS", response.status());
    assertEquals("output", response.output());
    assertNotNull(response.toString());
  }

  @Test
  void testTaskRequest() {
    TaskRequest request = new TaskRequest("session-1");
    assertEquals("session-1", request.sessionId());
  }
}
