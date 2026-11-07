// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import java.util.List;

/** Supervisor settings that do not match the type's schema; each issue names its field. */
public class SettingsValidationException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** The problems, at least one. */
  private final transient List<Issue> issues;

  /**
   * One problem.
   *
   * @param field the field, such as {@code settings.maxRisk}
   * @param message what is wrong; never repeats a value, which may be a secret
   */
  public record Issue(String field, String message) {}

  /**
   * Creates the exception.
   *
   * @param issues the problems
   */
  public SettingsValidationException(final List<Issue> issues) {
    this("Invalid settings", issues);
  }

  /**
   * Creates the exception with a lead for the message.
   *
   * @param what what was invalid, such as {@code Invalid supervisors}
   * @param issues the problems
   */
  public SettingsValidationException(final String what, final List<Issue> issues) {
    super(
        what
            + ": "
            + String.join("; ", issues.stream().map(i -> i.field() + " " + i.message()).toList()));
    this.issues = List.copyOf(issues);
  }

  /**
   * The problems.
   *
   * @return the issues
   */
  public List<Issue> getIssues() {
    return issues;
  }
}
