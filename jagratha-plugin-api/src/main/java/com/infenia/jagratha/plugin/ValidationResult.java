package com.infenia.jagratha.plugin;

import java.util.List;

/**
 * Result of plugin configuration validation.
 *
 * @param valid whether the configuration is valid
 * @param message general validation message
 * @param errors map of field names to error messages
 */
public record ValidationResult(boolean valid, String message, List<FieldError> errors) {

  /** Compact constructor to ensure immutability. */
  public ValidationResult {
    errors = errors != null ? List.copyOf(errors) : List.of();
  }

  /**
   * Create a successful validation result.
   *
   * @return the validation result
   */
  public static ValidationResult success() {
    return new ValidationResult(true, "Validation successful", List.of());
  }

  /**
   * Create a failed validation result with a message.
   *
   * @param message the error message
   * @return the validation result
   */
  public static ValidationResult error(final String message) {
    return new ValidationResult(false, message, List.of());
  }

  /**
   * Create a failed validation result with field errors.
   *
   * @param message general error message
   * @param errors field errors
   * @return the validation result
   */
  public static ValidationResult error(final String message, final List<FieldError> errors) {
    return new ValidationResult(false, message, errors);
  }

  /**
   * Field-specific validation error.
   *
   * @param field the field name
   * @param message the validation error message
   */
  public record FieldError(String field, String message) {}
}
