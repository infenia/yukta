// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static com.infenia.yukta.controller.MetricsRanges.withRange;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.metrics.RunAnalytics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** How long each workflow takes, for the Control dashboard. */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
@Tag(name = "Metrics API", description = "Recent history of server metrics")
@RegisterReflectionForBinding({RunAnalytics.WorkflowRow.class})
public class MetricsWorkflowsController {

  /** The per-session and per-workflow aggregates. */
  private final RunAnalytics analytics;

  /**
   * How long each workflow takes, slowest first.
   *
   * @param range 15m, 1h, 6h or 24h
   * @param limit most rows, 1 to 100
   * @return one row per workflow that had runs, by 95th percentile duration
   */
  @GetMapping("/workflows")
  @Operation(
      summary = "Workflow durations",
      description = "Runs, failures and run-duration percentiles per workflow")
  public Mono<ApiResponse<List<RunAnalytics.WorkflowRow>>> workflows(
      @RequestParam(defaultValue = "1h") final String range,
      @RequestParam(defaultValue = "20") final int limit) {
    return withRange(
        range,
        duration -> analytics.workflows(duration, Math.clamp(limit, 1, 100)),
        "Workflow stats retrieved");
  }
}
