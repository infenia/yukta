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
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("YuktaProperties")
class YuktaPropertiesTest {

  private YuktaProperties properties;

  @BeforeEach
  void setUp() {
    properties = new YuktaProperties();
  }

  @Test
  @DisplayName("noArgsConstructor creates instance successfully")
  void testNoArgsConstructor() {
    assertThat(properties).isNotNull();
  }

  @Test
  @DisplayName("default heartbeatIntervalSeconds has correct value")
  void testDefaultHeartbeatIntervalSeconds() {
    assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(10L);
  }

  @Test
  @DisplayName("setHeartbeatIntervalSeconds sets and retrieves correct value")
  void testSetHeartbeatIntervalSeconds() {
    properties.setHeartbeatIntervalSeconds(30L);
    assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(30L);
  }

  @Test
  @DisplayName("equals returns true for instances with same values")
  void testEqualsWithSameValues() {
    YuktaProperties other = new YuktaProperties();
    other.setHeartbeatIntervalSeconds(10L);

    assertThat(properties).isEqualTo(other);
  }

  @Test
  @DisplayName("equals returns false for instances with different values")
  void testEqualsWithDifferentValues() {
    YuktaProperties other = new YuktaProperties();
    other.setHeartbeatIntervalSeconds(20L);

    assertThat(properties).isNotEqualTo(other);
  }

  @Test
  @DisplayName("hashCode returns same value for instances with same heartbeatIntervalSeconds")
  void testHashCodeWithSameValues() {
    YuktaProperties other = new YuktaProperties();
    other.setHeartbeatIntervalSeconds(10L);

    assertThat(properties.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  @DisplayName(
      "hashCode returns different value for instances with different heartbeatIntervalSeconds")
  void testHashCodeWithDifferentValues() {
    YuktaProperties other = new YuktaProperties();
    other.setHeartbeatIntervalSeconds(20L);

    assertThat(properties.hashCode()).isNotEqualTo(other.hashCode());
  }

  @Test
  @DisplayName("toString contains class name and field values")
  void testToString() {
    properties.setHeartbeatIntervalSeconds(15L);
    String result = properties.toString();

    assertThat(result)
        .contains("YuktaProperties")
        .contains("heartbeatIntervalSeconds")
        .contains("15");
  }

  @Test
  @DisplayName("multiple property changes set and retrieve values independently")
  void testMultiplePropertyChanges() {
    properties.setHeartbeatIntervalSeconds(20L);
    assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(20L);

    properties.setHeartbeatIntervalSeconds(40L);
    assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(40L);

    properties.setHeartbeatIntervalSeconds(5L);
    assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(5L);
  }
}
