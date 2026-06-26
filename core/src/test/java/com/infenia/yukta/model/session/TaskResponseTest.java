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
package com.infenia.yukta.model.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskResponseTest {

  @Test
  void constructor_withValidStatusAndOutput_createsRecord() {
    // Given
    String status = "SUCCESS";
    String output = "Operation completed";

    // When
    TaskResponse response = new TaskResponse(status, output);

    // Then
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.output()).isEqualTo(output);
  }

  @Test
  void status_afterCreation_returnsProvidedStatus() {
    // Given
    TaskResponse response = new TaskResponse("FAILURE", "Error occurred");

    // When
    String result = response.status();

    // Then
    assertThat(result).isEqualTo("FAILURE");
  }

  @Test
  void output_afterCreation_returnsProvidedOutput() {
    // Given
    TaskResponse response = new TaskResponse("SUCCESS", "Error occurred");

    // When
    String result = response.output();

    // Then
    assertThat(result).isEqualTo("Error occurred");
  }

  @Test
  void equals_sameStatusAndOutput_returnsTrue() {
    // Given
    TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    TaskResponse response2 = new TaskResponse("SUCCESS", "Done");

    // When & Then
    assertThat(response1).isEqualTo(response2);
  }

  @Test
  void equals_differentStatus_returnsFalse() {
    // Given
    TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    TaskResponse response2 = new TaskResponse("FAILURE", "Done");

    // When & Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void equals_differentOutput_returnsFalse() {
    // Given
    TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    TaskResponse response2 = new TaskResponse("SUCCESS", "Failed");

    // When & Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void hashCode_sameStatusAndOutput_returnsSameHashCode() {
    // Given
    TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    TaskResponse response2 = new TaskResponse("SUCCESS", "Done");

    // When & Then
    assertThat(response1.hashCode()).isEqualTo(response2.hashCode());
  }

  @Test
  void hashCode_differentStatusAndOutput_returnsDifferentHashCode() {
    // Given
    TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    TaskResponse response2 = new TaskResponse("FAILURE", "Failed");

    // When & Then
    assertThat(response1.hashCode()).isNotEqualTo(response2.hashCode());
  }

  @Test
  void toString_validRecord_returnsFormattedString() {
    // Given
    TaskResponse response = new TaskResponse("SUCCESS", "Done");

    // When
    String result = response.toString();

    // Then
    assertThat(result).contains("SUCCESS").contains("Done");
  }

  @Test
  void constructor_withNullStatus_createsRecord() {
    // Given & When
    TaskResponse response = new TaskResponse(null, "Output");

    // Then
    assertThat(response.status()).isNull();
    assertThat(response.output()).isEqualTo("Output");
  }

  @Test
  void constructor_withNullOutput_createsRecord() {
    // Given & When
    TaskResponse response = new TaskResponse("SUCCESS", null);

    // Then
    assertThat(response.status()).isEqualTo("SUCCESS");
    assertThat(response.output()).isNull();
  }
}
