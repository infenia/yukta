// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Annotation for session ID validation. */
@NotBlank(message = "Session ID is required")
@Size(max = 256, message = "Session ID must be at most 256 characters")
@Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.ANNOTATION_TYPE,
  ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Documented
public @interface SessionId {
  /**
   * Message for validation failure.
   *
   * @return the error message
   */
  String message() default "Invalid session ID";

  /**
   * Groups for validation.
   *
   * @return the groups
   */
  Class<?>[] groups() default {};

  /**
   * Payload for validation.
   *
   * @return the payload
   */
  Class<? extends Payload>[] payload() default {};
}
