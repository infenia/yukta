// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.filter.AgentCredentialFilter;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.service.supervisor.agent.AgentHub;
import com.infenia.yukta.service.supervisor.agent.HttpEngineFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/** An AI agent that is a supervisor reaches the agent API through MCP tools. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class AgentMcpControllerTest {

  private final AgentHub hub = new AgentHub();
  private final com.infenia.yukta.service.supervisor.SupervisorHost host =
      org.mockito.Mockito.mock(com.infenia.yukta.service.supervisor.SupervisorHost.class);
  private final AgentMcpController mcp =
      new AgentMcpController(new AgentApiController(hub, host), new ObjectMapper());
  private final List<Directive> received = new ArrayList<>();

  private MockServerWebExchange running() {
    new HttpEngineFactory(hub)
        .create(
            new EngineConfig(Map.of()),
            new EngineContext() {
              @Override
              public String self() {
                return "a1";
              }

              @Override
              public Flux<Instant> ticks(final Duration period) {
                return Flux.never();
              }

              @Override
              public Map<String, Object> state() {
                return Map.of();
              }
            })
        .run(
            Flux.<Signal>just(
                    new Signal.Review(
                        "r1",
                        new com.infenia.yukta.plugin.control.ControlRequest(
                            new com.infenia.yukta.plugin.control.ExecutionControlCommand
                                .PauseWorkflowCommand("run1"),
                            null,
                            "s",
                            "w"),
                        Duration.ofMinutes(1)),
                    new Signal.Delegated(
                        "d1",
                        com.infenia.yukta.plugin.control.DelegationKind.APPROVAL,
                        "c",
                        Map.of()))
                .concatWith(Flux.never()))
        .subscribe(received::add);
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/agent/v1/mcp"));
    exchange.getAttributes().put(AgentCredentialFilter.SUPERVISOR_ATTRIBUTE, "a1");
    return exchange;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rpc(
      final MockServerWebExchange exchange, final String method, final Object params) {
    final Map<String, Object> message = new java.util.HashMap<>();
    message.put("jsonrpc", "2.0");
    message.put("id", 1);
    message.put("method", method);
    message.put("params", params);
    return java.util.Objects.requireNonNull(mcp.handle(exchange, message).block()).getBody();
  }

  private static Map<String, Object> call(final String name, final Map<String, Object> args) {
    return Map.of("name", name, "arguments", args);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(final Map<String, Object> response) {
    return (Map<String, Object>) response.get("result");
  }

  @Test
  void itAnswersTheHandshakeAndListsTheTools() {
    final MockServerWebExchange exchange = running();
    assertThat(result(rpc(exchange, "initialize", Map.of())).get("protocolVersion"))
        .isEqualTo("2025-03-26");
    assertThat(result(rpc(exchange, "ping", null))).isEmpty();
    assertThat((List<?>) result(rpc(exchange, "tools/list", null)).get("tools")).hasSize(11);
    assertThat(((Map<?, ?>) rpc(exchange, "nope", null).get("error")).get("code"))
        .isEqualTo(-32_601);
    assertThat(
            mcp.handle(exchange, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                .block()
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void toolsReadAndSendThroughTheAgentApi() {
    final MockServerWebExchange exchange = running();
    for (final String read : new String[] {"get_snapshot", "list_executions", "list_open_inputs"}) {
      final Map<String, Object> result =
          result(rpc(exchange, "tools/call", call(read, Map.of("limit", 5))));
      assertThat(result.get("isError")).isEqualTo(false);
    }
    // this engine reads nothing, so what is not owned is reported as not found, not as a crash
    for (final String read :
        new String[] {"get_execution", "get_logs", "get_workflow", "get_scheduler"}) {
      final Map<String, Object> result =
          result(
              rpc(
                  exchange,
                  "tools/call",
                  call(read, Map.of("executionId", "run1", "sessionId", "s", "workflowId", "w"))));
      assertThat(result.get("isError")).isEqualTo(true);
    }

    assertThat(
            result(
                    rpc(
                        exchange,
                        "tools/call",
                        call(
                            "send_directive",
                            Map.of(
                                "key",
                                "k",
                                "cause",
                                "tick",
                                "reason",
                                "r",
                                "command",
                                Map.of("type", "PauseWorkflow", "executionId", "run1")))))
                .get("isError"))
        .isEqualTo(false);
    rpc(exchange, "tools/call", call("answer_review", Map.of("id", "r1", "verdict", "ALLOW")));
    rpc(
        exchange,
        "tools/call",
        call("answer_delegation", Map.of("id", "d1", "answer", Map.of("x", 1))));
    assertThat(received).hasSize(3);
  }

  @Test
  void signalsArePolledWithOrWithoutCursorAndDefaultsApply() {
    final MockServerWebExchange exchange = running();
    final Map<String, Object> polled =
        result(
            rpc(
                exchange,
                "tools/call",
                call("next_signals", Map.of("after", 0, "waitSeconds", 0))));
    assertThat(polled.get("isError")).isEqualTo(false);
    assertThat(result(rpc(exchange, "tools/call", call("next_signals", Map.of()))).get("isError"))
        .isEqualTo(false);
    assertThat(
            result(rpc(exchange, "tools/call", call("list_open_inputs", Map.of("limit", "x"))))
                .get("isError"))
        .isEqualTo(false);
  }

  @Test
  void resultThatCannotBeWrittenIsToolError() {
    final ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new tools.jackson.core.JacksonException("boom") {});
    final AgentMcpController failing =
        new AgentMcpController(new AgentApiController(hub, host), broken);
    final MockServerWebExchange exchange = running();
    final Map<String, Object> message =
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            1,
            "method",
            "tools/call",
            "params",
            call("get_snapshot", Map.of()));
    final Map<?, ?> answer =
        (Map<?, ?>)
            java.util.Objects.requireNonNull(
                    java.util.Objects.requireNonNull(failing.handle(exchange, message).block())
                        .getBody())
                .get("result");
    assertThat(answer.get("isError")).isEqualTo(true);
    assertThat(answer.get("content").toString()).contains("could not be written");
  }

  @Test
  void failureIsToolErrorAndBadCallIsProtocolError() {
    final MockServerWebExchange exchange = running();
    final Map<String, Object> bad =
        result(rpc(exchange, "tools/call", call("send_directive", Map.of("key", "k"))));
    assertThat(bad.get("isError")).isEqualTo(true);
    assertThat(result(rpc(exchange, "tools/call", call("nope", Map.of()))).get("isError"))
        .isEqualTo(true);
    assertThat(((Map<?, ?>) rpc(exchange, "tools/call", Map.of()).get("error")).get("code"))
        .isEqualTo(-32_602);
    assertThat(((Map<?, ?>) rpc(exchange, "tools/call", "x").get("error")).get("code"))
        .isEqualTo(-32_602);
    assertThat(result(rpc(exchange, "tools/call", Map.of("name", "get_snapshot"))).get("isError"))
        .isEqualTo(false);
  }
}
