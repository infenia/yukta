// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Where the server may send outbound calls, in three layers: the whole deployment ({@code
 * yukta.egress.allowed-hosts}), one workflow ({@code yukta.egress.by-workflow.<id>}) and one
 * session ({@code yukta.egress.by-session.<id>}). A URL must pass every list that applies to the
 * run, so a workflow or session list can only narrow what the deployment allows. An empty or absent
 * list does not restrict.
 *
 * <p>The workflow and session are read from the Reactor context of the call, which the engine sets
 * for every node. Outside a run only the deployment list applies. HTTP_REQUEST, MCP_TOOL_CALL and
 * NOTIFY_TERMINAL check it before they connect, on top of their own host lists.
 *
 * <p>The check works on the name in the URL, as {@link HostAllowList} does. The address a name
 * resolves to is checked at connect time by the HTTP client's resolver, against the link-local and
 * metadata block only.
 */
public final class EgressPolicy {

  /** A policy that allows every host. */
  public static final EgressPolicy OPEN =
      new EgressPolicy(HostAllowList.from(null), Map.of(), Map.of());

  /** Reactor context key of the workflow id. */
  private static final String WORKFLOW_KEY = "workflowId";

  /** Reactor context key of the session id. */
  private static final String SESSION_KEY = "sessionId";

  /** The deployment-wide list. */
  private final HostAllowList deployment;

  /** Lists by workflow id. */
  private final Map<String, HostAllowList> byWorkflow;

  /** Lists by session id. */
  private final Map<String, HostAllowList> bySession;

  /**
   * Creates a policy.
   *
   * @param deployment hosts every call may reach
   * @param byWorkflow hosts a workflow's calls may reach, by workflow id
   * @param bySession hosts a session's calls may reach, by session id
   */
  public EgressPolicy(
      final HostAllowList deployment,
      final Map<String, HostAllowList> byWorkflow,
      final Map<String, HostAllowList> bySession) {
    this.deployment = deployment;
    this.byWorkflow = Map.copyOf(byWorkflow);
    this.bySession = Map.copyOf(bySession);
  }

  /**
   * Fails with an {@link IllegalArgumentException} when a URL may not be called from the current
   * run.
   *
   * @param url the URL about to be called
   * @return empty if the call is allowed
   */
  public Mono<Void> check(final String url) {
    return Mono.deferContextual(
        ctx -> {
          final String refusal = refusalFor(url, ctx);
          return refusal == null ? Mono.empty() : Mono.error(new IllegalArgumentException(refusal));
        });
  }

  /**
   * Why a URL may not be called from a run.
   *
   * @param url the URL about to be called
   * @param ctx the Reactor context of the call
   * @return the reason, or null if the call is allowed
   */
  public @Nullable String refusalFor(final String url, final ContextView ctx) {
    String refusal = deployment.refusalFor(url, "yukta.egress.allowed-hosts");
    if (refusal == null
        && !(byWorkflow.isEmpty() && bySession.isEmpty())
        && !ctx.hasKey(WORKFLOW_KEY)
        && !ctx.hasKey(SESSION_KEY)) {
      // per-scope lists are configured but the call cannot be tied to a run: fail closed, not open
      refusal =
          "The call has no workflowId or sessionId in its context, so it cannot be checked against"
              + " yukta.egress.by-workflow or by-session";
    }
    if (refusal == null) {
      refusal = layer(url, ctx, WORKFLOW_KEY, byWorkflow, "yukta.egress.by-workflow");
    }
    if (refusal == null) {
      refusal = layer(url, ctx, SESSION_KEY, bySession, "yukta.egress.by-session");
    }
    return refusal;
  }

  private static @Nullable String layer(
      final String url,
      final ContextView ctx,
      final String key,
      final Map<String, HostAllowList> lists,
      final String property) {
    final Object scopeId = ctx.getOrDefault(key, null);
    final HostAllowList list = scopeId == null ? null : lists.get(scopeId.toString());
    return list == null ? null : list.refusalFor(url, property + "." + scopeId);
  }
}
