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
package com.infenia.yukta.cli.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSystemEnvironmentProviderTest {

  private DefaultSystemEnvironmentProvider provider;

  @BeforeEach
  void setUp() {
    provider = new DefaultSystemEnvironmentProvider();
  }

  // getProperty() tests

  @Test
  void getProperty_existingProperty_returnsPropertyValue() {
    String result = provider.getProperty("java.version").orElse(null);
    assertThat(result).isNotNull().isNotEmpty();
  }

  @Test
  void getProperty_nonExistingProperty_returnsEmpty() {
    Optional<String> result = provider.getProperty("non.existent.property.xyz123");
    assertThat(result).isEmpty();
  }

  // getEnvironment() tests

  @Test
  void getEnvironment_pathEnvironment_returnsPathValue() {
    Optional<String> result = provider.getEnvironment("PATH");
    assertThat(result).isNotEmpty();
  }

  @Test
  void getEnvironment_nonExistingVariable_returnsEmpty() {
    Optional<String> result = provider.getEnvironment("NON_EXISTENT_ENV_VARIABLE_XYZ123");
    assertThat(result).isEmpty();
  }

  @Test
  void getProperty_returnsOptional() {
    Optional<String> result = provider.getProperty("java.version");
    assertThat(result).isInstanceOf(Optional.class);
  }

  @Test
  void getEnvironment_returnsOptional() {
    Optional<String> result = provider.getEnvironment("PATH");
    assertThat(result).isInstanceOf(Optional.class);
  }
}
