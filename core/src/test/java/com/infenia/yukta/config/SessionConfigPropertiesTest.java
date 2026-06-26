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

import org.junit.jupiter.api.Test;

class SessionConfigPropertiesTest {

  @Test
  void noArgsConstructor_createsInstance_succeeds() {
    // Given-When
    SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties).isNotNull();
  }

  @Test
  void defaultBaseDir_usesUserHomeProperty_correctPath() {
    // Given
    String expectedUserHome = System.getProperty("user.home");
    String expectedBaseDirValue = expectedUserHome + "/.yukta";

    // When
    SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getBaseDir()).isEqualTo(expectedBaseDirValue);
  }

  @Test
  void defaultFileLogSubDir_hasCorrectValue_modifiedFiles() {
    // Given
    String expectedFileLogSubDir = "modified-files";

    // When
    SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getFileLogSubDir()).isEqualTo(expectedFileLogSubDir);
  }

  @Test
  void defaultResultLogSubDir_hasCorrectValue_results() {
    // Given
    String expectedResultLogSubDir = "results";

    // When
    SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getResultLogSubDir()).isEqualTo(expectedResultLogSubDir);
  }

  @Test
  void defaultExecutionTimeoutSeconds_hasCorrectValue_3600() {
    // Given
    Long expectedExecutionTimeoutSeconds = 3600L;

    // When
    SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getExecutionTimeoutSeconds())
        .isEqualTo(expectedExecutionTimeoutSeconds);
  }

  @Test
  void defaultStoreType_hasCorrectValue_inMemory() {
    // Given
    String expectedStoreType = "in-memory";

    // When
    SessionConfigProperties actualProperties = new SessionConfigProperties();

    // Then
    assertThat(actualProperties.getStoreType()).isEqualTo(expectedStoreType);
  }

  @Test
  void setBaseDir_setsAndRetrieves_correctValue() {
    // Given
    SessionConfigProperties actualProperties = new SessionConfigProperties();
    String customBaseDirValue = "/custom/base/dir";

    // When
    actualProperties.setBaseDir(customBaseDirValue);

    // Then
    assertThat(actualProperties.getBaseDir()).isEqualTo(customBaseDirValue);
  }

  @Test
  void setFileLogSubDir_setsAndRetrieves_correctValue() {
    // Given
    SessionConfigProperties actualProperties = new SessionConfigProperties();
    String customFileLogSubDirValue = "custom-logs";

    // When
    actualProperties.setFileLogSubDir(customFileLogSubDirValue);

    // Then
    assertThat(actualProperties.getFileLogSubDir()).isEqualTo(customFileLogSubDirValue);
  }

  @Test
  void setResultLogSubDir_setsAndRetrieves_correctValue() {
    // Given
    SessionConfigProperties actualProperties = new SessionConfigProperties();
    String customResultLogSubDirValue = "custom-results";

    // When
    actualProperties.setResultLogSubDir(customResultLogSubDirValue);

    // Then
    assertThat(actualProperties.getResultLogSubDir()).isEqualTo(customResultLogSubDirValue);
  }

  @Test
  void setExecutionTimeoutSeconds_setsAndRetrieves_correctValue() {
    // Given
    SessionConfigProperties actualProperties = new SessionConfigProperties();
    Long customExecutionTimeoutSecondsValue = 7200L;

    // When
    actualProperties.setExecutionTimeoutSeconds(customExecutionTimeoutSecondsValue);

    // Then
    assertThat(actualProperties.getExecutionTimeoutSeconds())
        .isEqualTo(customExecutionTimeoutSecondsValue);
  }

  @Test
  void setStoreType_setsAndRetrieves_correctValue() {
    // Given
    SessionConfigProperties actualProperties = new SessionConfigProperties();
    String customStoreTypeValue = "database";

    // When
    actualProperties.setStoreType(customStoreTypeValue);

    // Then
    assertThat(actualProperties.getStoreType()).isEqualTo(customStoreTypeValue);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/custom/dir");
    properties1.setFileLogSubDir("files");
    properties1.setResultLogSubDir("results-custom");
    properties1.setExecutionTimeoutSeconds(5000L);
    properties1.setStoreType("redis");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/custom/dir");
    properties2.setFileLogSubDir("files");
    properties2.setResultLogSubDir("results-custom");
    properties2.setExecutionTimeoutSeconds(5000L);
    properties2.setStoreType("redis");

    // When-Then
    assertThat(properties1).isEqualTo(properties2);
  }

  @Test
  void equals_differentBaseDirValues_returnsFalse() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/dir1");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/dir2");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentFileLogSubDirValues_returnsFalse() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setFileLogSubDir("logs1");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setFileLogSubDir("logs2");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentResultLogSubDirValues_returnsFalse() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setResultLogSubDir("results1");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setResultLogSubDir("results2");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentExecutionTimeoutSecondsValues_returnsFalse() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setExecutionTimeoutSeconds(1000L);

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setExecutionTimeoutSeconds(2000L);

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void equals_differentStoreTypeValues_returnsFalse() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setStoreType("memory");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setStoreType("disk");

    // When-Then
    assertThat(properties1).isNotEqualTo(properties2);
  }

  @Test
  void hashCode_sameValues_sameHash() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/custom/dir");
    properties1.setFileLogSubDir("files");
    properties1.setResultLogSubDir("results-custom");
    properties1.setExecutionTimeoutSeconds(5000L);
    properties1.setStoreType("redis");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/custom/dir");
    properties2.setFileLogSubDir("files");
    properties2.setResultLogSubDir("results-custom");
    properties2.setExecutionTimeoutSeconds(5000L);
    properties2.setStoreType("redis");

    // When
    int actualHash1 = properties1.hashCode();
    int actualHash2 = properties2.hashCode();

    // Then
    assertThat(actualHash1).isEqualTo(actualHash2);
  }

  @Test
  void hashCode_differentValues_differentHash() {
    // Given
    SessionConfigProperties properties1 = new SessionConfigProperties();
    properties1.setBaseDir("/dir1");

    SessionConfigProperties properties2 = new SessionConfigProperties();
    properties2.setBaseDir("/dir2");

    // When
    int actualHash1 = properties1.hashCode();
    int actualHash2 = properties2.hashCode();

    // Then
    assertThat(actualHash1).isNotEqualTo(actualHash2);
  }

  @Test
  void toString_generatedMethod_containsAllFields() {
    // Given
    SessionConfigProperties actualProperties = new SessionConfigProperties();
    actualProperties.setBaseDir("/test/dir");
    actualProperties.setFileLogSubDir("test-files");
    actualProperties.setResultLogSubDir("test-results");
    actualProperties.setExecutionTimeoutSeconds(1000L);
    actualProperties.setStoreType("test-store");

    // When
    String actualToString = actualProperties.toString();

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
    SessionConfigProperties actualProperties = new SessionConfigProperties();

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
