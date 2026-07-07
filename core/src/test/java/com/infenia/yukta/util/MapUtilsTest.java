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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MapUtils}. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class MapUtilsTest {

  /** Nested path for testing. */
  private static final String NESTED_PATH = "a.b.c";

  /** Test value. */
  private static final String TEST_VALUE = "value";

  /** User name for testing. */
  private static final String USER_NAME = "John";

  /** Suppression literal for unchecked cast warnings, shared to avoid duplicate literals. */
  private static final String UNCHECKED = "unchecked";

  @Test
  @SuppressWarnings(UNCHECKED)
  void testSetNestedValue() {
    final Map<String, Object> map = new ConcurrentHashMap<>();
    MapUtils.setNestedValue(map, NESTED_PATH, TEST_VALUE);

    assertThat(map.get("a")).isNotNull().isInstanceOf(Map.class);
    final Map<String, Object> mapA = (Map<String, Object>) map.get("a");

    assertThat(mapA.get("b")).isNotNull().isInstanceOf(Map.class);
    final Map<String, Object> mapB = (Map<String, Object>) mapA.get("b");

    assertThat(mapB.get("c")).isEqualTo(TEST_VALUE);
  }

  @Test
  void testRemoveNestedValueNotMap() {
    final Map<String, Object> map = new ConcurrentHashMap<>();
    map.put("a", "not-a-map");
    MapUtils.removeNestedValue(map, "a.b");
    assertThat(map.get("a")).isEqualTo("not-a-map");
  }

  @Test
  void testGetNestedValue() {
    final Map<String, Object> map = Map.of("a", Map.of("b", Map.of("c", TEST_VALUE)));

    assertThat(MapUtils.getNestedValue(map, NESTED_PATH)).isEqualTo(TEST_VALUE);
    assertThat(MapUtils.getNestedValue(map, "a.b.d")).isNull();
    assertThat(MapUtils.getNestedValue(map, "x.y.z")).isNull();
    assertThat(MapUtils.getNestedValue(map, "a.b.c.d")).isNull();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testRemoveNestedValue() {
    final Map<String, Object> map = new ConcurrentHashMap<>();
    MapUtils.setNestedValue(map, NESTED_PATH, "value1");
    MapUtils.setNestedValue(map, "a.b.d", "value2");

    MapUtils.removeNestedValue(map, NESTED_PATH);

    final Map<String, Object> mapB =
        (Map<String, Object>) ((Map<String, Object>) map.get("a")).get("b");
    assertThat(mapB).doesNotContainKey("c");
    assertThat(mapB.get("d")).isEqualTo("value2");

    MapUtils.removeNestedValue(map, "a.b.x"); // Non-existent path
    assertThat(mapB).containsKey("d");

    MapUtils.removeNestedValue(map, "x.y.z"); // Root non-existent
    assertThat(map.get("a")).isNotNull();

    MapUtils.removeNestedValue(map, null);
    MapUtils.removeNestedValue(map, "");
    MapUtils.removeNestedValue(null, NESTED_PATH);
    assertThat(map.get("a")).isNotNull();
  }

  @Test
  void testFlatten() {
    final Map<String, Object> source =
        Map.of("user", Map.of("name", USER_NAME, "address", Map.of("city", "NY")), "id", 123);

    final Map<String, Object> result = MapUtils.flatten(source);

    assertThat(result)
        .hasSize(3)
        .contains(
            entry("user.name", USER_NAME), entry("user.address.city", "NY"), entry("id", 123));

    assertThat(MapUtils.flatten(null)).isEmpty();

    // Test with empty string key to trigger prefix.isEmpty() in else branch
    final Map<String, Object> emptyKeyMap = new ConcurrentHashMap<>();
    emptyKeyMap.put("", TEST_VALUE);
    final Map<String, Object> flatEmpty = MapUtils.flatten(emptyKeyMap);
    assertThat(flatEmpty).isEmpty();
  }

  @Test
  void testAsMutableMap() {
    final Map<String, Object> immutable = Map.of("key", TEST_VALUE);
    final Map<String, Object> mutable = MapUtils.asMutableMap(immutable);

    mutable.put("new", "val");
    assertThat(mutable).containsEntry("key", TEST_VALUE).containsEntry("new", "val");

    assertThat(MapUtils.asMutableMap(null)).isNotNull().isEmpty();

    record User(String name) {}

    final Map<String, Object> fromPojo = MapUtils.asMutableMap(new User(USER_NAME));
    assertThat(fromPojo.get("name")).isEqualTo(USER_NAME);
  }

  @Test
  void testConvert() {
    assertThat(MapUtils.convert("123", Integer.class)).isEqualTo(123);
    assertThat(MapUtils.convert(123, String.class)).isEqualTo("123");
    assertThat(MapUtils.convert(123, Integer.class)).isEqualTo(123);
    assertThat(MapUtils.convert(null, Integer.class)).isNull();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testSetNestedValueWithTrailingDot() {
    final Map<String, Object> map = new ConcurrentHashMap<>();
    MapUtils.setNestedValue(map, "a.b.", TEST_VALUE);

    final Map<String, Object> mapA = (Map<String, Object>) map.get("a");
    final Map<String, Object> mapB = (Map<String, Object>) mapA.get("b");
    assertThat(mapB).containsEntry("", TEST_VALUE);
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testSetNestedValueWithConsecutiveDots() {
    final Map<String, Object> map = new ConcurrentHashMap<>();
    MapUtils.setNestedValue(map, "a..b", TEST_VALUE);

    final Map<String, Object> mapA = (Map<String, Object>) map.get("a");
    final Map<String, Object> emptyMap = (Map<String, Object>) mapA.get("");
    assertThat(emptyMap).containsEntry("b", TEST_VALUE);
  }

  @Test
  void testGetNestedValueWithTrailingDot() {
    final Map<String, Object> map = Map.of("a", Map.of("b", Map.of("", TEST_VALUE)));

    assertThat(MapUtils.getNestedValue(map, "a.b.")).isEqualTo(TEST_VALUE);
  }

  @Test
  void testGetNestedValueWithConsecutiveDots() {
    final Map<String, Object> map = Map.of("a", Map.of("", Map.of("b", TEST_VALUE)));

    assertThat(MapUtils.getNestedValue(map, "a..b")).isEqualTo(TEST_VALUE);
  }
}
