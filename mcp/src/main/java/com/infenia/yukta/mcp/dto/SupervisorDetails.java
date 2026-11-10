// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One configured supervisor: its engine, settings (secrets hidden), parents and what it owns.
 *
 * @param id the supervisor id
 * @param type its decision maker (engine type)
 * @param description what it is for, or null
 * @param dryRun whether its commands are only recorded
 * @param settings its settings, secret-looking values as {@code ***}
 * @param parents the supervisors it reports to, in order
 * @param assignments what it owns
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorDetails(
    String id,
    String type,
    @Nullable String description,
    boolean dryRun,
    Map<String, Object> settings,
    List<String> parents,
    List<SupervisorAssignmentInfo> assignments) {

  /** Keeps immutable copies; a setting may be null. */
  public SupervisorDetails {
    settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    parents = List.copyOf(parents);
    assignments = List.copyOf(assignments);
  }
}
