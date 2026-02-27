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
package com.infenia.yukta.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Unified API response format for success and error responses.
 *
 * @param <T> the type of the response data
 * @param timestamp the time the response was generated
 * @param status the HTTP status code
 * @param message a human-readable message
 * @param data the response data (success only)
 * @param error the HTTP status error name (error only)
 * @param path the request path (error only)
 * @param errors the list of field validation errors (optional, error only)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Unified API response format")
public record ApiResponse<T>(
    @Schema(description = "The time the response was generated") LocalDateTime timestamp,
    @Schema(description = "The HTTP status code", example = "200") int status,
    @Schema(description = "A human-readable message", example = "Operation successful")
        String message,
    @Schema(description = "The response data") T data,
    @Schema(description = "The HTTP status error name", example = "Bad Request") String error,
    @Schema(description = "The request path", example = "/api/files") String path,
    @Schema(description = "The list of field validation errors") List<FieldError> errors) {

  /** Compact constructor to ensure immutability. */
  public ApiResponse {
    errors = errors != null ? List.copyOf(errors) : List.of();
  }

  /**
   * Field-specific validation error.
   *
   * @param field the field name
   * @param message the validation error message
   */
  @Schema(description = "Field-specific validation error")
  public record FieldError(
      @Schema(description = "The field name", example = "path") String field,
      @Schema(description = "The validation error message", example = "Path is required")
          String message) {}

  /**
   * Create a successful response.
   *
   * @param <T> the data type
   * @param status the HTTP status
   * @param message the success message
   * @param data the success data
   * @return a successful ApiResponse
   */
  public static <T> ApiResponse<T> success(final int status, final String message, final T data) {
    return new ApiResponse<>(LocalDateTime.now(), status, message, data, null, null, null);
  }

  /**
   * Create an error response.
   *
   * @param <T> the data type
   * @param status the HTTP status
   * @param error the error name
   * @param message the error message
   * @param path the request path
   * @param errors the field errors
   * @return an error ApiResponse
   */
  public static <T> ApiResponse<T> error(
      final int status,
      final String error,
      final String message,
      final String path,
      final List<FieldError> errors) {
    return new ApiResponse<>(
        LocalDateTime.now(),
        status,
        message,
        null,
        error,
        path,
        errors != null ? List.copyOf(errors) : List.of());
  }
}
