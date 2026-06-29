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
