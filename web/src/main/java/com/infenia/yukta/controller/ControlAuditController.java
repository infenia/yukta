// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.ControlAuditEntry;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

  /**
   * List the newest control commands.
   *
   * @param limit how many entries, 1 to 500
   * @param executionId only the commands of this run, or null
   * @return the entries, newest first (the log is in the order the commands were recorded)
   */
  @GetMapping("/control/audit")
  @Operation(
      summary = "List control commands",
      description =
          "Lists the newest control commands recorded in the execution log (applied or refused),"
              + " with the actor that sent them")
  public Mono<ApiResponse<List<ControlAuditEntry>>> list(
      @RequestParam(defaultValue = "100") final int limit,
      @RequestParam(required = false) final String executionId) {
    return Mono.fromCallable(executionLog::readAll)
        .subscribeOn(Schedulers.boundedElastic())
        .map(
            events -> {
              // newest first, without copying and reversing the whole list; stops at the limit
              final int max = Math.clamp(limit, 1, MAX_LIMIT);
              final List<ControlAuditEntry> entries = new ArrayList<>();
              for (int i = events.size() - 1; i >= 0 && entries.size() < max; i--) {
                final ExecutionLogEvent event = events.get(i);
                if (CONTROL.contains(event.type())
                    && (executionId == null || executionId.equals(event.executionId()))) {
                  entries.add(ControlAuditEntry.from(event));
                }
              }
              return entries;
            })
        .map(entries -> ApiResponse.success(200, "Control commands retrieved", entries));
  }
}
