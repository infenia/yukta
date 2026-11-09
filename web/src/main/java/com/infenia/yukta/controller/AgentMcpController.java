// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.filter.AgentCredentialFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent API as MCP tools, for an AI agent that is a supervisor: the same operations as {@link
 * AgentApiController}, with the same credential, on {@code POST /api/agent/v1/mcp} (JSON-RPC over
 * HTTP, request and response; no server push, so an agent reads the snapshot instead of waiting for
 * events). It is a separate endpoint from {@code /mcp}, which serves people and their keys.
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
@RestController
@RequestMapping(AgentCredentialFilter.PATH)
@RequiredArgsConstructor
public class AgentMcpController {

  private static final String PROTOCOL = "2025-03-26";
  private static final int METHOD_NOT_FOUND = -32_601;
  private static final int INVALID_PARAMS = -32_602;

  private static final String TYPE = "type";
  private static final String INTEGER_TYPE = "integer";
  private static final String STRING_TYPE = "string";
  private static final String LIMIT = "limit";
  private static final String REASON = "reason";

  private final AgentApiController api;
  private final ObjectMapper mapper;

  private static final List<Map<String, Object>> TOOLS =
      List.of(
          tool(
              "get_snapshot",
              "The executions and open input requests you supervise, with a cursor.",
              Map.of()),
          tool(
              "next_signals",
              "Signals after a cursor (from get_snapshot or the last call), waiting up to 25"
                  + " seconds for the first one; returns the new cursor.",
              Map.of(
                  "after", Map.of(TYPE, INTEGER_TYPE), "waitSeconds", Map.of(TYPE, INTEGER_TYPE))),
          tool(
              "list_executions",
              "The executions you supervise, newest first.",
              Map.of("status", Map.of(TYPE, STRING_TYPE), LIMIT, Map.of(TYPE, INTEGER_TYPE))),
          tool(
              "get_execution",
              "One execution you supervise, with the status of the nodes you own.",
              Map.of("executionId", Map.of(TYPE, STRING_TYPE))),
          tool(
              "get_logs",
              "The newest log lines of an execution you supervise (secrets removed, long lines"
                  + " cut).",
              Map.of("executionId", Map.of(TYPE, STRING_TYPE), "tail", Map.of(TYPE, INTEGER_TYPE))),
          tool(
              "get_workflow",
              "The nodes and edges of a workflow you own something in (no settings).",
              Map.of(
                  "sessionId", Map.of(TYPE, STRING_TYPE), "workflowId", Map.of(TYPE, STRING_TYPE))),
          tool(
              "get_scheduler",
              "The scheduler limits and load; only for the supervisor that owns the server.",
              Map.of()),
          tool(
              "list_open_inputs",
              "The open input requests of the units you supervise.",
              Map.of(LIMIT, Map.of(TYPE, INTEGER_TYPE))),
          tool(
              "send_directive",
              "Send a command: key, cause (event:<id>, answers:<id>, tick or initiative), reason,"
                  + " ifStatus and command {type, executionId, ...}. The supervisor checks it.",
              Map.of(
                  "key",
                  Map.of(TYPE, STRING_TYPE),
                  "cause",
                  Map.of(TYPE, STRING_TYPE),
                  REASON,
                  Map.of(TYPE, STRING_TYPE),
                  "ifStatus",
                  Map.of(TYPE, STRING_TYPE),
                  "command",
                  Map.of(TYPE, "object"))),
          tool(
              "answer_review",
              "Answer a review: id and verdict ALLOW, DENY, REQUIRE_APPROVAL or NO_OPINION.",
              Map.of(
                  "id",
                  Map.of(TYPE, STRING_TYPE),
                  "verdict",
                  Map.of(TYPE, STRING_TYPE),
                  REASON,
                  Map.of(TYPE, STRING_TYPE))),
          tool(
              "answer_delegation",
              "Answer a delegation from a child supervisor.",
              Map.of("id", Map.of(TYPE, STRING_TYPE), "answer", Map.of(TYPE, "object"))));

  private static Map<String, Object> tool(
      final String name, final String description, final Map<String, Object> properties) {
    return Map.of(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        Map.of(TYPE, "object", "properties", properties));
  }

  /**
   * Handles one JSON-RPC message.
   *
   * @param exchange the request
   * @param message the message
   * @return the JSON-RPC response; 202 without a body for a notification
   */
  @PostMapping("/mcp")
  public Mono<ResponseEntity<Map<String, Object>>> handle(
      final ServerWebExchange exchange, @RequestBody final Map<String, Object> message) {
    final Object id = message.get("id");
    final String method = String.valueOf(message.get("method"));
    if (id == null) {
      return Mono.just(ResponseEntity.accepted().build());
    }
    return switch (method) {
      case "initialize" ->
          reply(
              id,
              Map.of(
                  "protocolVersion",
                  PROTOCOL,
                  "capabilities",
                  Map.of("tools", Map.of()),
                  "serverInfo",
                  Map.of("name", "yukta-supervisor", "version", "1")));
      case "ping" -> reply(id, Map.of());
      case "tools/list" -> reply(id, Map.of("tools", TOOLS));
      case "tools/call" -> call(exchange, id, message.get("params"));
      default -> failure(id, METHOD_NOT_FOUND, "Unknown method " + method);
    };
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private Mono<ResponseEntity<Map<String, Object>>> call(
      final ServerWebExchange exchange, final Object id, final @Nullable Object params) {
    if (!(params instanceof Map<?, ?> p) || !(p.get("name") instanceof String name)) {
      return failure(id, INVALID_PARAMS, "tools/call needs a name");
    }
    final Map<String, Object> arguments = new LinkedHashMap<>();
    if (p.get("arguments") instanceof Map<?, ?> given) {
      given.forEach((k, v) -> arguments.put(String.valueOf(k), v));
    }
    return Mono.defer(() -> run(exchange, name, arguments))
        .map(data -> text(false, data))
        .onErrorResume(error -> Mono.just(text(true, error.getMessage())))
        .flatMap(result -> reply(id, result));
  }

  @SuppressWarnings("PMD.CyclomaticComplexity")
  private Mono<Object> run(
      final ServerWebExchange exchange, final String name, final Map<String, Object> a) {
    return switch (name) {
      case "get_snapshot" -> api.snapshot(exchange).map(r -> (Object) r.data());
      case "next_signals" ->
          api.next(
                  exchange,
                  a.get("after") instanceof Number n ? n.longValue() : 0L,
                  a.get("waitSeconds") instanceof Number w ? w.intValue() : 0)
              .map(r -> (Object) r.data());
      case "list_executions" ->
          api.executions(exchange, string(a, "status"), integer(a.get(LIMIT)))
              .map(r -> (Object) r.data());
      case "get_execution" ->
          api.execution(exchange, string(a, "executionId")).map(r -> (Object) r.data());
      case "get_logs" ->
          api.logs(exchange, string(a, "executionId"), integer(a.get("tail")))
              .map(r -> (Object) r.data());
      case "get_workflow" ->
          api.workflow(exchange, string(a, "sessionId"), string(a, "workflowId"))
              .map(r -> (Object) r.data());
      case "get_scheduler" -> api.scheduler(exchange).map(r -> (Object) r.data());
      case "list_open_inputs" ->
          api.inputs(exchange, integer(a.get(LIMIT))).map(r -> (Object) r.data());
      case "send_directive" ->
          api.directive(
                  exchange,
                  new AgentApiController.DirectiveBody(
                      string(a, "key"),
                      string(a, "cause"),
                      string(a, REASON),
                      string(a, "ifStatus"),
                      map(a.get("command"))))
              .thenReturn("accepted");
      case "answer_review" ->
          api.review(
                  exchange,
                  string(a, "id"),
                  new AgentApiController.VerdictBody(string(a, "verdict"), string(a, REASON)))
              .thenReturn("accepted");
      case "answer_delegation" ->
          api.delegation(
                  exchange,
                  string(a, "id"),
                  new AgentApiController.AnswerBody(map(a.get("answer"))))
              .thenReturn("accepted");
      default -> Mono.error(new IllegalArgumentException("Unknown tool " + name));
    };
  }

  private Map<String, Object> text(final boolean error, final @Nullable Object data) {
    final String body;
    try {
      body = data instanceof String s ? s : mapper.writeValueAsString(data);
    } catch (final JacksonException e) {
      return text(true, "The result could not be written: " + e.getMessage());
    }
    return Map.of("content", List.of(Map.of(TYPE, "text", "text", body)), "isError", error);
  }

  private static @Nullable String string(final Map<String, Object> a, final String key) {
    return a.get(key) == null ? null : a.get(key).toString();
  }

  private static int integer(final @Nullable Object value) {
    return value instanceof Number n ? n.intValue() : 50;
  }

  @SuppressWarnings("unchecked")
  private static @Nullable Map<String, Object> map(final @Nullable Object value) {
    return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  private static Mono<ResponseEntity<Map<String, Object>>> reply(
      final Object id, final Map<String, Object> result) {
    return Mono.just(ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
  }

  private static Mono<ResponseEntity<Map<String, Object>>> failure(
      final Object id, final int code, final String message) {
    return Mono.just(
        ResponseEntity.ok(
            Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", code, "message", message))));
  }
}
