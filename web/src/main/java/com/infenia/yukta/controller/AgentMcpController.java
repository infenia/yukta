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
import tools.jackson.databind.ObjectMapper;

/**
 * The agent API as MCP tools, for an AI agent that is a supervisor: the same operations as {@link
 * AgentApiController}, with the same credential, on {@code POST /api/agent/v1/mcp} (JSON-RPC over
 * HTTP, request and response; no server push, so an agent reads the snapshot instead of waiting for
 * events). It is a separate endpoint from {@code /mcp}, which serves people and their keys.
 */
@RestController
@RequestMapping(AgentCredentialFilter.PATH)
@RequiredArgsConstructor
public class AgentMcpController {

  private static final String PROTOCOL = "2025-03-26";
  private static final int METHOD_NOT_FOUND = -32601;
  private static final int INVALID_PARAMS = -32602;

  private final AgentApiController api;
  private final ObjectMapper mapper;

  private static Map<String, Object> tool(
      final String name, final String description, final Map<String, Object> properties) {
    return Map.of(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        Map.of("type", "object", "properties", properties));
  }

  private static final List<Map<String, Object>> TOOLS =
      List.of(
          tool(
              "get_snapshot",
              "The executions and open input requests you supervise, with a cursor.",
              Map.of()),
          tool(
              "list_executions",
              "The executions you supervise, newest first.",
              Map.of("status", Map.of("type", "string"), "limit", Map.of("type", "integer"))),
          tool(
              "list_open_inputs",
              "The open input requests of the units you supervise.",
              Map.of("limit", Map.of("type", "integer"))),
          tool(
              "send_directive",
              "Send a command: key, cause (event:<id>, answers:<id>, tick or initiative), reason,"
                  + " ifStatus and command {type, executionId, ...}. The supervisor checks it.",
              Map.of(
                  "key", Map.of("type", "string"),
                  "cause", Map.of("type", "string"),
                  "reason", Map.of("type", "string"),
                  "ifStatus", Map.of("type", "string"),
                  "command", Map.of("type", "object"))),
          tool(
              "answer_review",
              "Answer a review: id and verdict ALLOW, DENY, REQUIRE_APPROVAL or NO_OPINION.",
              Map.of(
                  "id", Map.of("type", "string"),
                  "verdict", Map.of("type", "string"),
                  "reason", Map.of("type", "string"))),
          tool(
              "answer_delegation",
              "Answer a delegation from a child supervisor.",
              Map.of("id", Map.of("type", "string"), "answer", Map.of("type", "object"))));

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

  private Mono<Object> run(
      final ServerWebExchange exchange, final String name, final Map<String, Object> a) {
    return switch (name) {
      case "get_snapshot" -> api.snapshot(exchange).map(r -> (Object) r.data());
      case "list_executions" ->
          api.executions(exchange, string(a, "status"), integer(a.get("limit")))
              .map(r -> (Object) r.data());
      case "list_open_inputs" ->
          api.inputs(exchange, integer(a.get("limit"))).map(r -> (Object) r.data());
      case "send_directive" ->
          api.directive(
                  exchange,
                  new AgentApiController.DirectiveBody(
                      string(a, "key"),
                      string(a, "cause"),
                      string(a, "reason"),
                      string(a, "ifStatus"),
                      map(a.get("command"))))
              .thenReturn("accepted");
      case "answer_review" ->
          api.review(
                  exchange,
                  string(a, "id"),
                  new AgentApiController.VerdictBody(string(a, "verdict"), string(a, "reason")))
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
    } catch (final RuntimeException e) {
      return text(true, "The result could not be written: " + e.getMessage());
    }
    return Map.of("content", List.of(Map.of("type", "text", "text", body)), "isError", error);
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
