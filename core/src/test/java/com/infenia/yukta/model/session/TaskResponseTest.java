// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link TaskResponse}. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class TaskResponseTest {

  @Test
  void constructor_withValidStatusAndOutput_createsRecord() {
    // Given
    final String status = "SUCCESS";
    final String output = "Operation completed";

    // When
    final TaskResponse response = new TaskResponse(status, output);

    // Then
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.output()).isEqualTo(output);
  }

  @Test
  void status_afterCreation_returnsProvidedStatus() {
    // Given
    final TaskResponse response = new TaskResponse("FAILURE", "Error occurred");

    // When
    final String result = response.status();

    // Then
    assertThat(result).isEqualTo("FAILURE");
  }

  @Test
  void output_afterCreation_returnsProvidedOutput() {
    // Given
    final TaskResponse response = new TaskResponse("SUCCESS", "Error occurred");

    // When
    final String result = response.output();

    // Then
    assertThat(result).isEqualTo("Error occurred");
  }

  @Test
  void equals_sameStatusAndOutput_returnsTrue() {
    // Given
    final TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    final TaskResponse response2 = new TaskResponse("SUCCESS", "Done");

    // When & Then
    assertThat(response1).isEqualTo(response2);
  }

  @Test
  void equals_differentStatus_returnsFalse() {
    // Given
    final TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    final TaskResponse response2 = new TaskResponse("FAILURE", "Done");

    // When & Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void equals_differentOutput_returnsFalse() {
    // Given
    final TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    final TaskResponse response2 = new TaskResponse("SUCCESS", "Failed");

    // When & Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void hashCode_sameStatusAndOutput_returnsSameHashCode() {
    // Given
    final TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    final TaskResponse response2 = new TaskResponse("SUCCESS", "Done");

    // When & Then
    assertThat(response1.hashCode()).isEqualTo(response2.hashCode());
  }

  @Test
  void hashCode_differentStatusAndOutput_returnsDifferentHashCode() {
    // Given
    final TaskResponse response1 = new TaskResponse("SUCCESS", "Done");
    final TaskResponse response2 = new TaskResponse("FAILURE", "Failed");

    // When & Then
    assertThat(response1.hashCode()).isNotEqualTo(response2.hashCode());
  }

  @Test
  @SuppressWarnings("PMD.LinguisticNaming")
  void toString_validRecord_returnsFormattedString() {
    // Given
    final TaskResponse response = new TaskResponse("SUCCESS", "Done");

    // When
    final String result = response.toString();

    // Then
    assertThat(result).contains("SUCCESS").contains("Done");
  }

  @Test
  void constructor_withNullStatus_createsRecord() {
    // Given & When
    final TaskResponse response = new TaskResponse(null, "Output");

    // Then
    assertThat(response.status()).isNull();
    assertThat(response.output()).isEqualTo("Output");
  }

  @Test
  void constructor_withNullOutput_createsRecord() {
    // Given & When
    final TaskResponse response = new TaskResponse("SUCCESS", null);

    // Then
    assertThat(response.status()).isEqualTo("SUCCESS");
    assertThat(response.output()).isNull();
  }
}
