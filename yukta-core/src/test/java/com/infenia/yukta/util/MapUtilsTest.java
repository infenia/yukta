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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapUtilsTest {

  @Test
  @SuppressWarnings("unchecked")
  void testSetNestedValue() {
    Map<String, Object> map = new HashMap<>();
    MapUtils.setNestedValue(map, "a.b.c", "value");

    assertNotNull(map.get("a"));
    assertTrue(map.get("a") instanceof Map);
    Map<String, Object> a = (Map<String, Object>) map.get("a");

    assertNotNull(a.get("b"));
    assertTrue(a.get("b") instanceof Map);
    Map<String, Object> b = (Map<String, Object>) a.get("b");

    assertEquals("value", b.get("c"));
  }

  @Test
  void testGetNestedValue() {
    Map<String, Object> map = Map.of("a", Map.of("b", Map.of("c", "value")));

    assertEquals("value", MapUtils.getNestedValue(map, "a.b.c"));
    assertNull(MapUtils.getNestedValue(map, "a.b.d"));
    assertNull(MapUtils.getNestedValue(map, "x.y.z"));
    assertNull(MapUtils.getNestedValue(map, "a.b.c.d"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testRemoveNestedValue() {
    Map<String, Object> map = new HashMap<>();
    MapUtils.setNestedValue(map, "a.b.c", "value1");
    MapUtils.setNestedValue(map, "a.b.d", "value2");

    MapUtils.removeNestedValue(map, "a.b.c");

    Map<String, Object> b = (Map<String, Object>) ((Map<String, Object>) map.get("a")).get("b");
    assertFalse(b.containsKey("c"));
    assertEquals("value2", b.get("d"));

    MapUtils.removeNestedValue(map, "a.b.x"); // Non-existent path
    assertTrue(b.containsKey("d"));

    MapUtils.removeNestedValue(map, "x.y.z"); // Root non-existent
    assertNotNull(map.get("a"));

    MapUtils.removeNestedValue(map, null);
    MapUtils.removeNestedValue(map, "");
    assertNotNull(map.get("a"));
  }

  @Test
  void testFlatten() {
    Map<String, Object> source =
        Map.of("user", Map.of("name", "John", "address", Map.of("city", "NY")), "id", 123);

    Map<String, Object> result = MapUtils.flatten(source);

    assertEquals(3, result.size());
    assertEquals("John", result.get("user.name"));
    assertEquals("NY", result.get("user.address.city"));
    assertEquals(123, result.get("id"));

    assertTrue(MapUtils.flatten(null).isEmpty());
  }

  @Test
  void testAsMutableMap() {
    Map<String, Object> immutable = Map.of("key", "value");
    Map<String, Object> mutable = MapUtils.asMutableMap(immutable);

    mutable.put("new", "val");
    assertEquals("value", mutable.get("key"));
    assertEquals("val", mutable.get("new"));

    assertNotNull(MapUtils.asMutableMap(null));
    assertTrue(MapUtils.asMutableMap(null).isEmpty());

    record User(String name) {}
    Map<String, Object> fromPojo = MapUtils.asMutableMap(new User("John"));
    assertEquals("John", fromPojo.get("name"));
  }

  @Test
  void testConvert() {
    assertEquals(Integer.valueOf(123), MapUtils.convert("123", Integer.class));
    assertEquals("123", MapUtils.convert(123, String.class));
    assertEquals(123, MapUtils.convert(123, Integer.class));
    assertNull(MapUtils.convert(null, Integer.class));
  }
}
