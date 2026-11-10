// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The saved states of a supervisor, newest first. Settings are not shown, because they may hold
 * secrets: only their names.
 *
 * @param supervisorId the supervisor
 * @param versions its versions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorVersionList(String supervisorId, List<Version> versions) {

  /** Keeps an immutable copy of the versions. */
  public SupervisorVersionList {
    versions = List.copyOf(versions);
  }

  /**
   * One saved state.
   *
   * @param version its number
   * @param savedAt when it was saved, ISO-8601
   * @param type the supervisor type
   * @param dryRun whether commands were only recorded
   * @param settingKeys the names of its settings
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Version(
      int version, String savedAt, String type, boolean dryRun, List<String> settingKeys) {

    /** Keeps an immutable copy of the setting names. */
    public Version {
      settingKeys = List.copyOf(settingKeys);
    }
  }
}
