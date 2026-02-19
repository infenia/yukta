package com.infenia.jagratha.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Annotation for configuration key validation. */
@NotBlank(message = "Configuration key cannot be blank")
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.ANNOTATION_TYPE,
  ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Documented
public @interface ConfigKey {
  /**
   * Message for validation failure.
   *
   * @return the error message
   */
  String message() default "Configuration key cannot be blank";

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
