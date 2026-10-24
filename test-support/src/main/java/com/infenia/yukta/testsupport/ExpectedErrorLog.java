// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test (or a whole test class) that is expected to log an ERROR or to drop a Reactor
 * signal. Each value is a regular expression searched in the event text: for a log event, the
 * message and the exception ({@code toString()}); for a dropped signal, {@code "dropped error: "}
 * or {@code "dropped next: "} and the value. Anything that matches no value is an offender of
 * {@link ErrorGuardExtension}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ExpectedErrorLog {

  /** Regular expressions, each searched (not fully matched) in an event's text. */
  String[] value() default {};

  /**
   * Like {@link #value()}, for an error that depends on timing and so happens in some runs only (a
   * connection closing while a signal is in flight). It is allowed, and never reported as unused.
   */
  String[] sometimes() default {};
}
