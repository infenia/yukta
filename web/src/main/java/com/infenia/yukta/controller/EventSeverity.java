// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How much attention an execution event deserves, from least to most. Lets a watcher ask for {@code
 * WARN} and up instead of reading every status change and log line.
 */
@SuppressWarnings({"PMD.ShortMethodName", "PMD.OnlyOneReturn"})
public enum EventSeverity {
  /** Normal progress. */
  INFO,
  /** Something to look at that did not stop the run: a rejected command, a warning line. */
  WARN,
  /** A node or run that failed, or an error line. */
  ERROR;

  /** Final statuses that mean a node or run failed. */
  private static final Set<String> FAILED = Set.of("FAILURE", "TIMED_OUT", "ABORTED");

  /** Words that make a log line an error. */
  private static final Pattern ERROR_LINE =
      Pattern.compile(
          "\\b(error|failed|failure)\\b|exception|What went wrong", Pattern.CASE_INSENSITIVE);

  /** Words that make a log line a warning. */
  private static final Pattern WARN_LINE =
      Pattern.compile("\\bwarn(ing)?\\b|deprecat", Pattern.CASE_INSENSITIVE);

  /**
   * Classifies an event. A node that ended {@code UPSTREAM_FAILED} is only the consequence of the
   * node before it, so it stays {@code INFO}.
   *
   * @param event the event
   * @return its severity
   */
  public static EventSeverity of(final ExecutionEvent event) {
    return switch (event) {
      case ExecutionEvent.NodeStatusChanged node -> ofStatus(node.status());
      case ExecutionEvent.WorkflowStatusChanged workflow -> ofStatus(workflow.status());
      case ExecutionEvent.ControlOutcome control -> control.applied() ? INFO : WARN;
      case ExecutionEvent.LogLine log -> ofLine(log.line());
    };
  }

  /**
   * Parses the minimum severity a caller asked for.
   *
   * @param name the name, in any case
   * @return the severity
   * @throws IllegalArgumentException if the name is not a severity
   */
  public static EventSeverity parse(final String name) {
    try {
      return valueOf(name.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("severity must be INFO, WARN or ERROR: " + name, e);
    }
  }

  private static EventSeverity ofStatus(final String status) {
    if (FAILED.contains(status)) {
      return ERROR;
    }
    return "CANCELLED".equals(status) ? WARN : INFO;
  }

  // ponytail: keyword match on the text, since log lines carry no level; a plugin that reports
  // one (a level on LogLine) should replace this.
  private static EventSeverity ofLine(final String line) {
    if (ERROR_LINE.matcher(line).find()) {
      return ERROR;
    }
    return WARN_LINE.matcher(line).find() ? WARN : INFO;
  }
}
