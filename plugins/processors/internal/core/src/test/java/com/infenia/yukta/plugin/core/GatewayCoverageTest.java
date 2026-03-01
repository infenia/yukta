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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.gateway.WorkflowGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayCoverageTest {

  @Test
  void testWorkflowGatewayNoOp() {
    final WorkflowGateway gateway = mock(WorkflowGateway.class);
    assertNotNull(gateway);
  }

  @Test
  void testSimpleExpressionEvaluatorCoverage() {
    final Message<Map<String, Object>> message =
        DefaultMessage.create(UUID.randomUUID(), Map.of("key", "val"));

    assertTrue(SimpleExpressionEvaluator.evaluate("payload.key == 'val'", message));
    assertFalse(SimpleExpressionEvaluator.evaluate("payload.key == 'wrong'", message));

    SimpleExpressionEvaluator.preParse("payload.key exists");
    assertTrue(SimpleExpressionEvaluator.evaluate("payload.key exists", message));

    assertTrue(SimpleExpressionEvaluator.evaluate("payload.key matches 'v.*'", message));
  }
}
