// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import java.time.LocalDateTime;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Narrows a session's history: every field that is set must match.
 *
 * @param workflowId only runs of this workflow
 * @param status only runs with this status (not case sensitive)
 * @param text only runs whose execution id contains this text (not case sensitive)
 * @param since only runs that started at or after this time
 * @param until only runs that started at or before this time
 */
public record HistoryFilter(
    @Nullable String workflowId,
    @Nullable String status,
    @Nullable String text,
    @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
    @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {

  /**
   * Whether a run passes the filter.
   *
   * @param run the run
   * @return true if every field that is set matches
   */
  public boolean matches(final WorkflowExecutionSummary run) {
    return (workflowId == null || workflowId.equals(run.workflowId()))
        && (status == null || status.equalsIgnoreCase(run.status()))
        && (text == null
            || run.executionId().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))
        && (since == null || (run.startTime() != null && !run.startTime().isBefore(since)))
        && (until == null || (run.startTime() != null && !run.startTime().isAfter(until)));
  }
}
