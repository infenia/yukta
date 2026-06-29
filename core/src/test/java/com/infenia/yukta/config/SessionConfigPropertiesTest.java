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

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link SessionConfigProperties}. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class SessionConfigPropertiesTest {
  /** Custom directory path constant. */
  private static final String CUSTOM_DIR = "/custom/dir";

  /** Files subdirectory constant. */
  private static final String FILES = "files";

  /** Results custom subdirectory constant. */
  private static final String RESULTS_CUSTOM = "results-custom";

  /** Redis store type constant. */
  private static final String REDIS = "redis";

  @Test
  void noArgsConstructor_createsInstance_succeeds() {
    // Given-When
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties).isNotNull();
  }

  @Test
  void defaultBaseDir_usesUserHomeProperty_correctPath() {
    // Given
    final String expectedUserHome = System.getProperty("user.home");
    final String expectedBaseDirValue = expectedUserHome + "/.yukta";

    // When
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getBaseDir()).isEqualTo(expectedBaseDirValue);
  }

  @Test
  void defaultFileLogSubDir_hasCorrectValue_modifiedFiles() {
    // Given
    final String expectedFileLogSubDir = "modified-files";

    // When
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getFileLogSubDir()).isEqualTo(expectedFileLogSubDir);
  }

  @Test
  void defaultResultLogSubDir_hasCorrectValue_results() {
    // Given
    final String expectedResultLogSubDir = "results";

    // When
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getResultLogSubDir()).isEqualTo(expectedResultLogSubDir);
  }

  @Test
  void defaultExecutionTimeoutSecondsHasCorrectValue_3600() {
    // Given
    final Long expectedTimeout = 3600L;

    // When
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getExecutionTimeoutSeconds()).isEqualTo(expectedTimeout);
  }

  @Test
  void defaultStoreType_hasCorrectValue_inMemory() {
    // Given
    final String expectedStoreType = "in-memory";

    // When
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getStoreType()).isEqualTo(expectedStoreType);
  }

  @Test
  void setBaseDir_setsAndRetrieves_correctValue() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();
    final String customBaseDirValue = "/custom/base/dir";

    // When
    actualProperties.setBaseDir(customBaseDirValue);

    // Then
    assertThat(actualProperties.getBaseDir()).isEqualTo(customBaseDirValue);
  }

  @Test
  void setFileLogSubDir_setsAndRetrieves_correctValue() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();
    final String customFileLogSubDirValue = "custom-logs";

    // When
    actualProperties.setFileLogSubDir(customFileLogSubDirValue);

    // Then
    assertThat(actualProperties.getFileLogSubDir()).isEqualTo(customFileLogSubDirValue);
  }

  @Test
  void setResultLogSubDir_setsAndRetrieves_correctValue() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();
    final String customResultLogSubDirValue = "custom-results";

    // When
    actualProperties.setResultLogSubDir(customResultLogSubDirValue);

    // Then
    assertThat(actualProperties.getResultLogSubDir()).isEqualTo(customResultLogSubDirValue);
  }

  @Test
  void setExecutionTimeoutSeconds_setsAndRetrieves_correctValue() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();
    final Long customTimeoutValue = 7200L;

    // When
    actualProperties.setExecutionTimeoutSeconds(customTimeoutValue);

    // Then
    assertThat(actualProperties.getExecutionTimeoutSeconds()).isEqualTo(customTimeoutValue);
  }

  @Test
  void setStoreType_setsAndRetrieves_correctValue() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();
    final String customStoreTypeValue = "database";

    // When
    actualProperties.setStoreType(customStoreTypeValue);

    // Then
    assertThat(actualProperties.getStoreType()).isEqualTo(customStoreTypeValue);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir(CUSTOM_DIR);
    properties1.setFileLogSubDir(FILES);
    properties1.setResultLogSubDir(RESULTS_CUSTOM);
    properties1.setExecutionTimeoutSeconds(5000L);
    properties1.setStoreType(REDIS);

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir(CUSTOM_DIR);
    properties2.setFileLogSubDir(FILES);
    properties2.setResultLogSubDir(RESULTS_CUSTOM);
    properties2.setExecutionTimeoutSeconds(5000L);
    properties2.setStoreType(REDIS);

    // When-Then
    assertThat(properties1).isEqualTo(properties2);
  }

  @Test
  void equals_differentBaseDirValues_returnsFalse() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/dir1");

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/dir2");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentFileLogSubDirValues_returnsFalse() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setFileLogSubDir("logs1");

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setFileLogSubDir("logs2");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentResultLogSubDirValues_returnsFalse() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setResultLogSubDir("results1");

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setResultLogSubDir("results2");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentExecutionTimeoutSecondsValues_returnsFalse() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setExecutionTimeoutSeconds(1000L);

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setExecutionTimeoutSeconds(2000L);

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentStoreTypeValues_returnsFalse() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setStoreType("memory");

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setStoreType("disk");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void hashCode_sameValues_sameHash() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/custom/dir");
    properties1.setFileLogSubDir("files");
    properties1.setResultLogSubDir("results-custom");
    properties1.setExecutionTimeoutSeconds(5000L);
    properties1.setStoreType("redis");

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/custom/dir");
    properties2.setFileLogSubDir("files");
    properties2.setResultLogSubDir("results-custom");
    properties2.setExecutionTimeoutSeconds(5000L);
    properties2.setStoreType("redis");

    // When
    final int actualHash1 = properties1.hashCode();
    final int actualHash2 = properties2.hashCode();

    // Then
    assertThat(actualHash1).isEqualTo(actualHash2);
  }

  @Test
  void hashCode_differentValues_differentHash() {
    // Given
    final SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/dir1");

    final SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/dir2");

    // When
    final int actualHash1 = properties1.hashCode();
    final int actualHash2 = properties2.hashCode();

    // Then
    assertThat(actualHash1).isNotEqualTo(actualHash2);
  }

  @Test
  void testToString_generatedMethod_containsAllFields() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();
    actualProperties.setBaseDir("/test/dir");
    actualProperties.setFileLogSubDir("test-files");
    actualProperties.setResultLogSubDir("test-results");
    actualProperties.setExecutionTimeoutSeconds(1000L);
    actualProperties.setStoreType("test-store");

    // When
    final String actualToString = actualProperties.toString();

    // Then
    assertThat(actualToString)
        .contains("SessionConfigProperties")
        .contains("baseDir")
        .contains("/test/dir")
        .contains("fileLogSubDir")
        .contains("test-files")
        .contains("resultLogSubDir")
        .contains("test-results")
        .contains("executionTimeoutSeconds")
        .contains("1000")
        .contains("storeType")
        .contains("test-store");
  }

  @Test
  void multiplePropertyChanges_setsIndependently_returnsCorrectValues() {
    // Given
    final SessionConfigProperties actualProperties = new SessionConfigProperties();

    // When
    actualProperties.setBaseDir("/path1");
    actualProperties.setFileLogSubDir("logs");
    actualProperties.setResultLogSubDir("res");
    actualProperties.setExecutionTimeoutSeconds(2000L);
    actualProperties.setStoreType("memory");

    // Then
    assertThat(actualProperties.getBaseDir()).isEqualTo("/path1");
    assertThat(actualProperties.getFileLogSubDir()).isEqualTo("logs");
    assertThat(actualProperties.getResultLogSubDir()).isEqualTo("res");
    assertThat(actualProperties.getExecutionTimeoutSeconds()).isEqualTo(2000L);
    assertThat(actualProperties.getStoreType()).isEqualTo("memory");
  }
}
