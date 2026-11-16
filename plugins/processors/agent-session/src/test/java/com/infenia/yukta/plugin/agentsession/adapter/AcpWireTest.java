// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The JSON-RPC framing of the Agent Client Protocol. */
@NoArgsConstructor
class AcpWireTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<String> written = new ArrayList<>();
  private final List<String> seen = new ArrayList<>();

  private final AcpWire wire =
      new AcpWire(
          new com.infenia.yukta.plugin.process.ProcessExecutorGateway.StdinWriter() {
            @Override
            public void write(final String line) {
              written.add(line);
            }

            @Override
            public void close() {
              written.add("closed");
            }
          },
          JSON);

  private final AcpWire.Listener peer =
      new AcpWire.Listener() {
        @Override
        public void request(final JsonNode id, final String method, final JsonNode params) {
          seen.add("request " + id + " " + method + " " + params);
        }

        @Override
        public void notification(final String method, final JsonNode params) {
          seen.add("notification " + method + " " + params);
        }
      };

  @Test
  void requestsAreNumberedAndAnsweredByTheirId() {
    final var first = wire.request("a", Map.of("x", 1));
    final var second = wire.request("b", Map.of());
    wire.accept("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"n\":2}}", peer);
    wire.accept("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"n\":1}}", peer);

    StepVerifier.create(first).expectNextMatches(r -> r.path("n").asInt() == 1).verifyComplete();
    StepVerifier.create(second).expectNextMatches(r -> r.path("n").asInt() == 2).verifyComplete();
    assertThat(written.get(0))
        .contains("\"id\":1")
        .contains("\"method\":\"a\"")
        .contains("\"x\":1");
    assertThat(written.get(1)).contains("\"id\":2");
  }

  @Test
  void errorAnswerFailsTheRequestWithItsCodeAndMessage() {
    final var answer = wire.request("a", Map.of());
    wire.accept(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"no\"}}", peer);

    StepVerifier.create(answer)
        .expectErrorSatisfies(
            e -> assertThat(e).isInstanceOf(AcpWire.RpcException.class).hasMessage("no"))
        .verify();
  }

  @Test
  void errorAnswerWithoutDetailsStillFails() {
    final var answer = wire.request("a", Map.of());
    wire.accept("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{}}", peer);

    StepVerifier.create(answer).expectErrorMessage("agent error").verify();
  }

  @Test
  void answerToNobodyIsIgnored() {
    assertThat(wire.accept("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}", peer)).isTrue();
    assertThat(seen).isEmpty();
  }

  @Test
  void requestsAndNotificationsOfTheAgentGoToThePeerWithTheIdAsItCame() {
    assertThat(
            wire.accept("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"m\",\"params\":{}}", peer))
        .isTrue();
    assertThat(wire.accept("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"n\"}", peer)).isTrue();
    assertThat(wire.accept("{\"jsonrpc\":\"2.0\",\"method\":\"u\",\"params\":{\"a\":1}}", peer))
        .isTrue();

    assertThat(seen)
        .containsExactly("request \"1\" m {}", "request 7 n ", "notification u {\"a\":1}");
  }

  @Test
  void repliesEchoTheIdOfTheRequest() throws Exception {
    wire.reply(JSON.readTree("\"1\""), Map.of("ok", true));
    wire.fail(JSON.readTree("5"), -32_601, "Method not found");
    wire.notify("session/cancel", Map.of("sessionId", "s"));

    assertThat(written.get(0))
        .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}");
    assertThat(written.get(1))
        .isEqualTo(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"error\":{\"code\":-32601,\"message\":\"Method not"
                + " found\"}}");
    assertThat(written.get(2))
        .isEqualTo(
            "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"s\"}}");
  }

  @Test
  void linesThatAreNotMessagesAreNotAccepted() {
    assertThat(wire.accept("starting up", peer)).isFalse();
    assertThat(wire.accept("{broken", peer)).isFalse();
    assertThat(wire.accept("[1]", peer)).isFalse();
    assertThat(wire.accept("{\"jsonrpc\":\"2.0\"}", peer)).isFalse();
    assertThat(wire.accept("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}", peer)).isFalse();
  }
}
