// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.ActivityHistoryEntry;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.activity.ActivityStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Past events of the activity feed. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Events API", description = "Execution events")
public class ActivityHistoryController {

  private static final int MAX_LIMIT = 500;

  private final ActivityStore store;
  private final ObjectMapper mapper;

  /**
   * List past events, newest first.
   *
   * @param limit how many, 1 to 500
   * @param before only events with a lower {@code seq}, to read the next older page
   * @param type only these kinds, comma-separated (see {@code /api/events/stream})
   * @param severity only these severities, comma-separated
   * @return the events
   */
  @GetMapping("/events/history")
  @Operation(
      summary = "List past events",
      description =
          "The newest events of the activity feed, kept in memory or, with PostgreSQL, durably."
              + " Log lines are not kept. Page back with before.")
  public Mono<ApiResponse<java.util.List<ActivityHistoryEntry>>> history(
      @RequestParam(defaultValue = "100") final int limit,
      @Parameter(description = "Only events with a lower seq") @RequestParam(required = false)
          final Long before,
      @RequestParam(required = false) final String type,
      @RequestParam(required = false) final String severity) {
    return store
        .newest(split(type, false), split(severity, true), before, Math.clamp(limit, 1, MAX_LIMIT))
        .map(e -> new ActivityHistoryEntry(e.seq(), mapper.readTree(e.json())))
        .collectList()
        .map(list -> ApiResponse.success(200, "Events retrieved", list));
  }

  private static Set<String> split(final String csv, final boolean upper) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Set.of(
        (upper ? csv.strip().toUpperCase(java.util.Locale.ROOT) : csv.strip()).split("\\s*,\\s*"));
  }
}
