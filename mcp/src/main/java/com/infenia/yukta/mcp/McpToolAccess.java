// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Which API key may call which MCP tool: the role it needs and, for session keys, the session. */
@UtilityClass
class McpToolAccess {

  /** Role a tool needs; a tool not listed here needs ADMIN. */
  private static final Map<String, ApiRole> REQUIRED =
      Map.ofEntries(
          Map.entry("get_session_details", ApiRole.VIEWER),
          Map.entry("list_sessions", ApiRole.VIEWER),
          Map.entry("get_execution_logs", ApiRole.VIEWER),
          Map.entry("get_execution_result", ApiRole.VIEWER),
          Map.entry("list_pending_approvals", ApiRole.VIEWER),
          Map.entry("list_workflow_versions", ApiRole.VIEWER),
          Map.entry("diff_workflow_versions", ApiRole.VIEWER),
          Map.entry("rollback_workflow", ApiRole.OPERATOR),
          Map.entry("note_workflow_version", ApiRole.OPERATOR),
          Map.entry("get_workflow_details", ApiRole.VIEWER),
          Map.entry("get_workflow_status", ApiRole.VIEWER),
          Map.entry("get_workflow_history", ApiRole.VIEWER),
          Map.entry("get_execution_tree", ApiRole.VIEWER),
          Map.entry("whoami", ApiRole.VIEWER),
          Map.entry("get_scheduler_limits", ApiRole.VIEWER),
          Map.entry("get_metrics_snapshot", ApiRole.VIEWER),
          Map.entry("get_node_output", ApiRole.VIEWER),
          Map.entry("list_plugins", ApiRole.VIEWER),
          Map.entry("get_plugin_details", ApiRole.VIEWER),
          Map.entry("get_control_bus_status", ApiRole.VIEWER),
          Map.entry("get_session_creation_instructions", ApiRole.VIEWER),
          Map.entry("get_plugin_creation_guide", ApiRole.VIEWER),
          Map.entry("start_workflow", ApiRole.OPERATOR),
          Map.entry("control_workflow", ApiRole.OPERATOR),
          Map.entry("control_node", ApiRole.OPERATOR),
          Map.entry("set_node_breakpoint", ApiRole.OPERATOR),
          Map.entry("signal_node", ApiRole.OPERATOR));

  /** Tools that name no session and show nothing of one, so a session key may call them. */
  private static final Set<String> SESSIONLESS =
      Set.of(
          "list_plugins",
          "get_plugin_details",
          "get_session_creation_instructions",
          "get_plugin_creation_guide",
          "whoami");

  /** Resources that name no session, so a session key may read them. */
  private static final Set<String> SESSIONLESS_RESOURCES =
      Set.of("yukta://overview", "yukta://architecture");

  /** Prefix of the resources that belong to a session: {@code yukta://sessions/<id>/...}. */
  private static final String SESSION_RESOURCE = "yukta://sessions/";

  /** Reads JSON-RPC messages. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Whether the key may send these JSON-RPC messages: every {@code tools/call} and {@code
   * resources/read} in them must be allowed, other methods (initialize, tools/list, notifications)
   * are not restricted.
   *
   * @param key the key that sent the request
   * @param body the request body, one message or a batch
   * @return false if a call is not allowed or the body is not JSON
   */
  /* default */ boolean permitted(final ApiKeys.Match key, final byte[] body) {
    try {
      final JsonNode root = JSON.readTree(body);
      for (final JsonNode message : root.isArray() ? root : List.of(root)) {
        final JsonNode params = message.path("params");
        if ("tools/call".equals(message.path("method").asString(""))
            && !permitted(
                key,
                params.path("name").asString(""),
                params.path("arguments").path("sessionId").asString(null))) {
          return false;
        }
        if ("resources/read".equals(message.path("method").asString(""))
            && !permittedResource(key, params.path("uri").asString(""))) {
          return false;
        }
      }
      return true;
    } catch (JacksonException e) {
      return false;
    }
  }

  /**
   * Whether the key may call the tool.
   *
   * @param key the key that sent the request
   * @param tool the tool name
   * @param sessionId the {@code sessionId} argument, or null
   * @return true if the role is high enough and, for a session key, the session is its own
   */
  /* default */ boolean permitted(
      final ApiKeys.Match key, final String tool, @Nullable final String sessionId) {
    if (key.role().compareTo(REQUIRED.getOrDefault(tool, ApiRole.ADMIN)) < 0) {
      return false;
    }
    if (key.sessions().isEmpty() || SESSIONLESS.contains(tool)) {
      return true;
    }
    return sessionId != null && key.reaches(sessionId);
  }

  /**
   * How many calls in these JSON-RPC messages are costly: {@code start_workflow}, and {@code
   * get_execution_logs} with a {@code filterPattern}, whose regex is matched against every line.
   *
   * @param body the request body, one message or a batch
   * @return the count; 0 if the body is not JSON
   */
  /* default */ int heavyCalls(final byte[] body) {
    try {
      final JsonNode root = JSON.readTree(body);
      int heavy = 0;
      for (final JsonNode message : root.isArray() ? root : List.of(root)) {
        if (!"tools/call".equals(message.path("method").asString(""))) {
          continue;
        }
        final JsonNode params = message.path("params");
        final String tool = params.path("name").asString("");
        if ("start_workflow".equals(tool)
            || ("get_execution_logs".equals(tool)
                && !params.path("arguments").path("filterPattern").asString("").isBlank())) {
          heavy++;
        }
      }
      return heavy;
    } catch (JacksonException e) {
      return 0;
    }
  }

  /**
   * Whether the key may read the resource: the one of a session needs the session to be the key's
   * (and the VIEWER role of {@code get_execution_logs}), one that names none is open to any key. A
   * resource this class does not know needs an unrestricted ADMIN key.
   *
   * @param key the key that sent the request
   * @param uri the {@code uri} of the {@code resources/read} request
   * @return true if the key may read it
   */
  /* default */ boolean permittedResource(final ApiKeys.Match key, final String uri) {
    if (SESSIONLESS_RESOURCES.contains(uri)) {
      return true; // VIEWER is the lowest role
    }
    if (uri.startsWith(SESSION_RESOURCE)) {
      final int end = uri.indexOf('/', SESSION_RESOURCE.length());
      final String sessionId =
          uri.substring(SESSION_RESOURCE.length(), end < 0 ? uri.length() : end);
      return permitted(key, "get_execution_logs", sessionId.isEmpty() ? null : sessionId);
    }
    return key.role().compareTo(ApiRole.ADMIN) >= 0 && key.sessions().isEmpty();
  }
}
