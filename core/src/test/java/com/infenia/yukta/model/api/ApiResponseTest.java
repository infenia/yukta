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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

  @Test
  void testSuccess() {
    ApiResponse<String> resp = ApiResponse.success(200, "ok", "data");
    assertNotNull(resp.timestamp());
    assertEquals(200, resp.status());
    assertEquals("ok", resp.message());
    assertEquals("data", resp.data());
    assertNull(resp.error());
    assertTrue(resp.errors().isEmpty());
  }

  @Test
  void testError() {
    ApiResponse.FieldError fieldError = new ApiResponse.FieldError("f", "m");
    ApiResponse<Void> resp = ApiResponse.error(400, "Bad", "msg", "/p", List.of(fieldError));

    assertEquals(400, resp.status());
    assertEquals("Bad", resp.error());
    assertEquals("msg", resp.message());
    assertEquals("/p", resp.path());
    assertEquals(1, resp.errors().size());
    assertEquals("f", resp.errors().get(0).field());
    assertEquals("m", resp.errors().get(0).message());
  }

  @Test
  void testErrorWithNullErrors() {
    ApiResponse<Void> resp = ApiResponse.error(500, "Error", "msg", "/p", null);
    assertNotNull(resp.errors());
    assertTrue(resp.errors().isEmpty());
  }

  @Test
  void testConstructorWithNullErrors() {
    ApiResponse<String> resp =
        new ApiResponse<>(java.time.LocalDateTime.now(), 200, "ok", "data", null, null, null);
    assertNotNull(resp.errors());
    assertTrue(resp.errors().isEmpty());
  }
}
