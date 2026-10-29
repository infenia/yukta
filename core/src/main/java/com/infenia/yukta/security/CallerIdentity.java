// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import reactor.util.context.ContextView;

/**
 * Who is calling and what they may do, for clients that would otherwise show every button and fail
 * with 403. The REST and MCP filters put the key they resolved into the Reactor context under
 * {@link #CONTEXT_KEY}; no key in the context means no key is configured.
 */
@UtilityClass
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn", "PMD.NullAssignment"})
public class CallerIdentity {

  /** Reactor context key of the {@link ApiKeys.Match} the filter resolved. */
  public static final String CONTEXT_KEY = "yukta.principal";

  /**
   * What the caller may do, from the role rules ({@link ApiRole#allows}) on a representative path
   * of each capability. A key limited to sessions cannot reach the paths that name none.
   *
   * @param startRuns start workflows
   * @param controlRuns pause, resume, stop and otherwise control runs
   * @param editSessions create or replace sessions
   * @param deleteSessions delete sessions and workflows
   * @param changeLimits change the execution limits
   * @param sendCommands send raw commands to nodes
   */
  public record Permissions(
      boolean startRuns,
      boolean controlRuns,
      boolean editSessions,
      boolean deleteSessions,
      boolean changeLimits,
      boolean sendCommands) {}

  /**
   * The answer to "who am I".
   *
   * @param authenticated whether the request carried a key
   * @param keyName the key's name ({@code default} for {@code yukta.api-key}), or null
   * @param role the role; ADMIN when no key is configured
   * @param sessions the only sessions the key may reach, or null for all
   * @param authEnabled whether any key is configured
   * @param permissions what the caller may do
   */
  public record Whoami(
      boolean authenticated,
      @Nullable String keyName,
      ApiRole role,
      @Nullable List<String> sessions,
      boolean authEnabled,
      Permissions permissions) {
    public Whoami {
      sessions = sessions == null ? null : List.copyOf(sessions);
    }
  }

  /**
   * Describes the caller of the current request.
   *
   * @param context the Reactor context of the request
   * @return the identity; never contains the key or its hash
   */
  public Whoami whoami(final ContextView context) {
    final ApiKeys.Match match = context.getOrDefault(CONTEXT_KEY, null);
    if (match == null) {
      return new Whoami(false, null, ApiRole.ADMIN, null, false, permissions(ApiRole.ADMIN, false));
    }
    final boolean scoped = !match.sessions().isEmpty();
    return new Whoami(
        true,
        match.name(),
        match.role(),
        scoped ? match.sessions().stream().sorted().toList() : null,
        true,
        permissions(match.role(), scoped));
  }

  private Permissions permissions(final ApiRole role, final boolean scoped) {
    return new Permissions(
        role.allows(HttpMethod.POST, "/api/workflow/start"),
        role.allows(HttpMethod.POST, "/api/executions/x/pause"),
        !scoped && role.allows(HttpMethod.POST, "/api/sessions"),
        !scoped && role.allows(HttpMethod.DELETE, "/api/sessions/x"),
        !scoped && role.allows(HttpMethod.POST, "/api/scheduler/limits"),
        !scoped && role.allows(HttpMethod.POST, "/api/control/workflows/x/nodes/y/command"));
  }
}
