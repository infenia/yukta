// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ApiResponse. */
@NoArgsConstructor
class ApiResponseTest {

  @Test
  void testSuccess() {
    final ApiResponse<String> resp = ApiResponse.success(200, "ok", "data");
    assertThat(resp.timestamp()).isNotNull();
    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.message()).isEqualTo("ok");
    assertThat(resp.data()).isEqualTo("data");
    assertThat(resp.error()).isNull();
    assertThat(resp.errors()).isEmpty();
  }

  @Test
  void testError() {
    final ApiResponse.FieldError fieldError = new ApiResponse.FieldError("f", "m");
    final ApiResponse<Void> resp = ApiResponse.error(400, "Bad", "msg", "/p", List.of(fieldError));

    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.error()).isEqualTo("Bad");
    assertThat(resp.message()).isEqualTo("msg");
    assertThat(resp.path()).isEqualTo("/p");
    assertThat(resp.errors()).hasSize(1);
    assertThat(resp.errors().get(0).field()).isEqualTo("f");
    assertThat(resp.errors().get(0).message()).isEqualTo("m");
  }

  @Test
  void testErrorWithNullErrors() {
    final ApiResponse<Void> resp = ApiResponse.error(500, "Error", "msg", "/p", null);
    assertThat(resp.errors()).isNotNull().isEmpty();
  }

  @Test
  void testConstructorWithNullErrors() {
    final ApiResponse<String> resp =
        new ApiResponse<>(
            java.time.LocalDateTime.now(ZoneId.systemDefault()),
            200,
            "ok",
            "data",
            null,
            null,
            null);
    assertThat(resp.errors()).isNotNull().isEmpty();
  }
}
