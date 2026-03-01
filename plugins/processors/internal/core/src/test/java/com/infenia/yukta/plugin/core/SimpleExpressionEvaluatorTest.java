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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimpleExpressionEvaluatorTest {

  @Test
  void testEquals() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));
    assertTrue(SimpleExpressionEvaluator.evaluate("payload.status == 'ACTIVE'", msg));
    assertFalse(SimpleExpressionEvaluator.evaluate("payload.status == 'INACTIVE'", msg));
  }

  @Test
  void testExists() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));
    assertTrue(SimpleExpressionEvaluator.evaluate("payload.status exists", msg));
    assertFalse(SimpleExpressionEvaluator.evaluate("payload.priority exists", msg));
  }

  @Test
  void testMatches() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("email", "test@example.com"));
    assertTrue(SimpleExpressionEvaluator.evaluate("payload.email matches '.*@example.com'", msg));
    assertFalse(SimpleExpressionEvaluator.evaluate("payload.email matches '.*@gmail.com'", msg));
  }

  @Test
  void testNested() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("user", Map.of("id", 123)));
    assertTrue(SimpleExpressionEvaluator.evaluate("payload.user.id == '123'", msg));
  }

  @Test
  void testMetadata() {
    final Message<?> msg =
        new DefaultMessage<>(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            null,
            null,
            0L,
            null,
            Map.of("priority", "HIGH"),
            java.util.List.of(),
            null,
            0,
            0,
            0,
            false,
            null,
            null,
            null,
            0,
            "data",
            java.time.Instant.now(),
            null,
            null);
    assertTrue(SimpleExpressionEvaluator.evaluate("metadata.priority == 'HIGH'", msg));
  }
}
