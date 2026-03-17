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
package com.infenia.yukta.model.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.model.monitoring.WorkflowProgress;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowProgressTest {

  @Test
  void testWorkflowProgress() {
    WorkflowProgress progress = new WorkflowProgress("e", "s", "w", "st", List.of(), null, null);
    assertEquals("e", progress.executionId());
    assertNotNull(progress.tasks());
    assertTrue(progress.tasks().isEmpty());
  }
}
