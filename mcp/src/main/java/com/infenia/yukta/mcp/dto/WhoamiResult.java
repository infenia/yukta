// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

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
 */
public record WhoamiResult(
    boolean authenticated,
    @Nullable String keyName,
    String role,
    @Nullable List<String> sessions,
    boolean authEnabled,
    Map<String, Boolean> permissions) {

  /** Compact constructor to ensure immutability. */
  public WhoamiResult {
    sessions = sessions == null ? null : List.copyOf(sessions);
    permissions = Map.copyOf(permissions);
  }
}
