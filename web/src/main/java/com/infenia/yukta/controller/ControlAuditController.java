// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.ControlAuditEntry;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Who sent which control command, from the execution log. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Control audit API", description = "Control commands and who sent them")
public class ControlAuditController {

  /** Most entries one call returns. */
  private static final int MAX_LIMIT = 500;

  /** The event types that record a control command. */
  private static final Set<ExecutionLogEvent.Type> CONTROL =
      Set.of(ExecutionLogEvent.Type.CONTROL_APPLIED, ExecutionLogEvent.Type.CONTROL_REJECTED);

  /** The execution log. */
  private final ExecutionLogStore executionLog;

  /** Knows the session and workflow of the runs it still holds. */
  private final TaskTrackerService tracker;

  /**
   * List the newest control commands.
   *
   * @param limit how many entries, 1 to 500
   * @param executionId only the commands of this run, or null
   * @param before only commands recorded before this instant (the {@code time} of the oldest entry
   *     of the page before), or null for the newest
   * @return the entries, newest first (the log is in the order the commands were recorded)
   */
  @GetMapping("/control/audit")
  @Operation(
      summary = "List control commands",
      description =
          "Lists the newest control commands recorded in the execution log (applied or refused),"
              + " with the actor that sent them. Page back with before, the time of the oldest"
              + " entry received")
  public Mono<ApiResponse<List<ControlAuditEntry>>> list(
      @RequestParam(defaultValue = "100") final int limit,
      @RequestParam(required = false) final String executionId,
      @RequestParam(required = false) final Instant before) {
    final int count = Math.clamp(limit, 1, MAX_LIMIT);
    return (before == null
            ? executionLog.newest(CONTROL, executionId, count)
            : executionLog.newestBefore(CONTROL, executionId, before, count))
        .map(ControlAuditEntry::from)
        .map(this::withRun)
        .collectList()
        .map(entries -> ApiResponse.success(200, "Control commands retrieved", entries));
  }

  private ControlAuditEntry withRun(final ControlAuditEntry entry) {
    final WorkflowProgress run =
        entry.executionId().isBlank()
            ? null
            : tracker.getProgressByExecutionId(entry.executionId());
    return run == null ? entry : entry.withRun(run.sessionId(), run.workflowId());
  }
}
