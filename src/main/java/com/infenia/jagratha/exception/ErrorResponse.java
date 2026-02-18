package com.infenia.jagratha.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public record ErrorResponse(
    LocalDateTime timestamp,
    int status,
    String error,
    String message,
    String path,
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
  public record FieldError(String field, String message) {}
}
