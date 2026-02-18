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
