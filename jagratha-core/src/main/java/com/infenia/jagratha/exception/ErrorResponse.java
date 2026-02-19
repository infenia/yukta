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
package com.infenia.jagratha.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Structured error response for API exceptions.
 *
 * @param timestamp the time the error occurred
 * @param status the HTTP status code
 * @param error the HTTP status error name
 * @param message the error message
 * @param path the request path
 * @param errors the list of field validation errors (optional)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Structured error response for API exceptions")
public record ErrorResponse(
    @Schema(description = "The time the error occurred") LocalDateTime timestamp,
    @Schema(description = "The HTTP status code", example = "400") int status,
    @Schema(description = "The HTTP status error name", example = "Bad Request") String error,
    @Schema(description = "The error message", example = "Invalid input") String message,
    @Schema(description = "The request path", example = "/api/files") String path,
    @Schema(description = "The list of field validation errors (optional)")
        List<FieldError> errors) {

  /** Compact constructor to ensure immutability. */
  public ErrorResponse {
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
}
