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
package com.infenia.yukta.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class SpelUtilsTest {

  @Test
  void testEvaluateAsync() {
    Map<String, Object> root = Map.of("key", "val");
    Map<String, Object> vars = Map.of("v1", "hello");

    StepVerifier.create(SpelUtils.evaluate("key + '-' + #v1", root, vars))
        .expectNext("val-hello")
        .verifyComplete();

    // Null variables
    StepVerifier.create(SpelUtils.evaluate("key", root, null)).expectNext("val").verifyComplete();
  }

  @Test
  void testEvaluateSync() {
    Map<String, Object> root = Map.of("key", "val");
    Map<String, Object> vars = Map.of("v1", "hello");

    assertEquals("val-hello", SpelUtils.evaluateSync("key + '-' + #v1", root, vars));
    assertEquals("val", SpelUtils.evaluateSync("key", root));

    // Check cleanup of variables
    assertNull(SpelUtils.evaluateSync("#v1", root));
  }

  @Test
  void testEvaluateSyncEmptyVars() {
    Map<String, Object> root = Map.of("key", "val");
    assertEquals("val", SpelUtils.evaluateSync("key", root, Map.of()));
  }

  @Test
  void testPreParse() {
    SpelUtils.preParse("1 + 1");
    SpelUtils.preParse(null);
    SpelUtils.preParse("");
    SpelUtils.preParse("   ");

    assertEquals(2, (Integer) SpelUtils.evaluateSync("1 + 1", null));
  }

  @Test
  void testConstructor() throws Exception {
    java.lang.reflect.Constructor<SpelUtils> constructor = SpelUtils.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test
  void testEvaluateReturnsNull() {
    assertNull(SpelUtils.evaluateSync("null", null));
    StepVerifier.create(SpelUtils.evaluate("null", null, null)).expectNextCount(0).verifyComplete();
  }

  @Test
  void testConcurrentEvaluateSync() {
    // Basic test to ensure ThreadLocal context works across multiple calls
    Map<String, Object> root1 = Map.of("v", 1);
    Map<String, Object> root2 = Map.of("v", 2);

    assertEquals(1, (Integer) SpelUtils.evaluateSync("v", root1));
    assertEquals(2, (Integer) SpelUtils.evaluateSync("v", root2));
    assertEquals(1, (Integer) SpelUtils.evaluateSync("v", root1));
  }
}
