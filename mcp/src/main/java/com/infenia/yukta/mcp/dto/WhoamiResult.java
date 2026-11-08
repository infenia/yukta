// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Who is calling and what they may do. Never contains the key or its hash.
 *
 * @param authenticated whether the request carried a key
 * @param keyName the key's name, or null when no key is configured
 * @param role VIEWER, OPERATOR or ADMIN (ADMIN when no key is configured)
 * @param sessions the only sessions the key may reach, or null for all
 * @param authEnabled whether any key is configured
 * @param permissions what the caller may do: startRuns, controlRuns, editSessions, deleteSessions,
 *     changeLimits, sendCommands
 * @param supervision {@code defaultSupervisor}: whether people control what nobody owns; {@code
 *     owners}: how many supervisors own something the caller can reach. A refusal naming a
 *     supervisor is its decision, not an error to retry.
 */
@SuppressWarnings("PMD.NullAssignment")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WhoamiResult(
    boolean authenticated,
    @Nullable String keyName,
    String role,
    @Nullable List<String> sessions,
    boolean authEnabled,
    Map<String, Boolean> permissions,
    @Nullable Map<String, Object> supervision) {

  /** Compact constructor to ensure immutability. */
  public WhoamiResult {
    sessions = sessions == null ? null : List.copyOf(sessions);
    permissions = Map.copyOf(permissions);
    supervision = supervision == null ? null : Map.copyOf(supervision);
  }
}
