// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.NcssCount",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidUsingVolatile",
  "PMD.CyclomaticComplexity",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.ShortVariable",
  "PMD.TooManyMethods",
  "PMD.CognitiveComplexity",
  "PMD.NPathComplexity",
  "PMD.OnlyOneReturn",
  "PMD.LawOfDemeter"
})
@NoArgsConstructor
class McpClientGatewayTest {

  private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");

  private final List<String> methods = new CopyOnWriteArrayList<>();
  private final AtomicInteger beats = new AtomicInteger();
  private final AtomicInteger expireNext = new AtomicInteger();
  private volatile boolean sse;
  private volatile boolean initFail;
  private volatile boolean listError;
  private volatile int paged;
  private final AtomicInteger cursors = new AtomicInteger();
  private volatile boolean stateless;
  private HttpServer server;
  private McpClientGateway gateway;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/mcp", this::handle);
    server.start();
    gateway = new McpClientGateway(WebClient.builder());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void handle(final HttpExchange exchange) throws IOException {
    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final var matcher = ID.matcher(body);
    final String id = matcher.find() ? matcher.group(1) : "";
    String method = "";
    for (final String m :
        List.of(
            "initialize",
            "notifications/initialized",
            "tools/call",
            "tools/list",
            "notifications/cancelled")) {
      if (body.contains("\"" + m + "\"")) {
        method = m;
      }
    }
    methods.add(method);
    String reply = "";
    if ("initialize".equals(method)) {
      if (!stateless) {
        exchange.getResponseHeaders().add("Mcp-Session-Id", "s1");
      }
      reply =
          initFail
              ? "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"error\":{\"message\":\"no\"}}"
              : "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{}}";
    } else if ("tools/list".equals(method)) {
      if (expireNext.getAndSet(0) == 1) {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      reply =
          listError
              ? rpc(id, "\"error\":{\"message\":\"nope\"}")
              : "{\"jsonrpc\":\"2.0\",\"id\":\""
                  + id
                  + "\",\"result\":{\"tools\":[{\"name\":\"weather\",\"description\":\"Weather\","
                  + "\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"bare\"},\"junk\"]}}";
      if (paged == 1) {
        reply =
            body.contains("\"cursor\":\"p2\"")
                ? rpc(id, "\"result\":{\"tools\":[{\"name\":\"second\"}]}")
                : rpc(id, "\"result\":{\"tools\":[{\"name\":\"first\"}],\"nextCursor\":\"p2\"}");
      } else if (paged == 2) {
        reply =
            rpc(
                id,
                "\"result\":{\"tools\":[{\"name\":\"again\"}],\"nextCursor\":\"c"
                    + cursors.incrementAndGet()
                    + "\"}");
      } else if (paged == 3) {
        reply =
            body.contains("\"cursor\":\"\"")
                ? rpc(id, "\"result\":{\"tools\":[{\"name\":\"last\"}]}")
                : rpc(id, "\"result\":{\"tools\":[{\"name\":\"only\"}],\"nextCursor\":\"\"}");
      } else if (paged == 4) {
        reply = rpc(id, "\"result\":{\"tools\":[{\"name\":\"loop\"}],\"nextCursor\":\"same\"}");
      }
      exchange.getResponseHeaders().add("Content-Type", "application/json");
    } else if ("tools/call".equals(method)) {
      if (body.contains("slow")) {
        try {
          Thread.sleep(1500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (body.contains("http500")) {
        reply = "kaput";
        exchange.sendResponseHeaders(500, reply.length());
        exchange.getResponseBody().write(reply.getBytes(StandardCharsets.UTF_8));
        exchange.close();
        return;
      }
      if (expireNext.getAndSet(0) == 1) {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      final String callId = body.contains("noreply") ? "other" : id;
      final String result =
          body.contains("rpcerror")
              ? rpc(id, "\"error\":{\"message\":\"nope\"}")
              : body.contains("garbage")
                  ? "not json"
                  : "{\"jsonrpc\":\"2.0\",\"id\":\""
                      + callId
                      + "\",\"result\":{\"structuredContent\":{\"a\":1},"
                      + "\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],"
                      + "\"isError\":"
                      + body.contains("fail")
                      + "}}";
      if (sse) {
        reply =
            "data: \n\n"
                + "data:"
                + " {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                + "data: "
                + result
                + "\n\n";
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      } else {
        reply = result;
        exchange.getResponseHeaders().add("Content-Type", "application/json");
      }
    }
    final byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(reply.isEmpty() ? 202 : 200, reply.isEmpty() ? -1 : bytes.length);
    if (!reply.isEmpty()) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private McpCallSpec spec(final String tool) {
    return new McpCallSpec(
        "http://localhost:" + server.getAddress().getPort() + "/mcp",
        tool,
        Map.of("q", "x"),
        Map.of(),
        Map.of(),
        Duration.ofSeconds(5));
  }

  @Test
  void call_handshakesThenCallsTool() {
    StepVerifier.create(gateway.call(spec("ping"), beats::incrementAndGet))
        .assertNext(
            r -> {
              assertThat(r.isError()).isFalse();
              assertThat(r.text()).isEqualTo("pong");
            })
        .verifyComplete();
    assertThat(methods).containsExactly("initialize", "notifications/initialized", "tools/call");
  }

  @Test
  void listTools_handshakesThenListsWithDefaults() {
    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .assertNext(
            tools ->
                assertThat(tools)
                    .containsExactly(
                        Map.of(
                            "name",
                            "weather",
                            "description",
                            "Weather",
                            "inputSchema",
                            Map.of("type", "object")),
                        Map.of("name", "bare", "description", "", "inputSchema", Map.of())))
        .verifyComplete();
    assertThat(methods).containsExactly("initialize", "notifications/initialized", "tools/list");
  }

  @Test
  void listTools_followsNextCursorToTheLastPage() {
    paged = 1;

    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .assertNext(
            tools ->
                assertThat(tools)
                    .extracting(tool -> tool.get("name"))
                    .containsExactly("first", "second"))
        .verifyComplete();
    assertThat(methods)
        .containsExactly("initialize", "notifications/initialized", "tools/list", "tools/list");
  }

  @Test
  void listTools_failsOnEndlessOrRepeatedCursors() {
    paged = 2;
    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("did not end"));

    paged = 4;
    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("did not end"));
  }

  @Test
  void listTools_followsAnEmptyCursorLikeAnyOther() {
    paged = 3;
    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .assertNext(
            tools ->
                assertThat(tools)
                    .extracting(tool -> tool.get("name"))
                    .containsExactly("only", "last"))
        .verifyComplete();
  }

  @Test
  void listTools_reinitializesWhenSessionExpires_andReportsRpcErrors() {
    gateway.listTools(spec("tools/list"), () -> {}).block();
    expireNext.set(1);
    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .expectNextCount(1)
        .verifyComplete();
    assertThat(methods).filteredOn("initialize"::equals).hasSize(2);

    listError = true;
    StepVerifier.create(gateway.listTools(spec("tools/list"), () -> {}))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("nope"));
  }

  @Test
  void call_refusesToolsOutsideTheGatewayPolicy() {
    gateway.setAllowedTools(List.of("other"));

    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .expectErrorMatches(e -> e.getMessage().contains("allowed-tools"))
        .verify();
    assertThat(methods).isEmpty();
  }

  @Test
  void call_countsArgumentBytesNotCharacters() {
    gateway.setMaxArgumentBytes(14);
    final McpCallSpec accents =
        new McpCallSpec(
            spec("ping").url(),
            "ping",
            Map.of("q", "éééé"),
            Map.of(),
            Map.of(),
            Duration.ofSeconds(5));

    StepVerifier.create(gateway.call(accents, () -> {}))
        .expectErrorMatches(e -> e.getMessage().contains("max-argument-bytes"))
        .verify();
  }

  @Test
  void call_allowsToolsInTheGatewayPolicy() {
    gateway.setAllowedTools(List.of("ping"));

    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .assertNext(r -> assertThat(r.text()).isEqualTo("pong"))
        .verifyComplete();
  }

  @Test
  void call_refusesOversizedAndDeniedArguments() {
    gateway.setMaxArgumentBytes(5);
    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .expectErrorMatches(e -> e.getMessage().contains("max-argument-bytes"))
        .verify();

    gateway.setMaxArgumentBytes(1000);
    gateway.setDenyPatterns(List.of("(?i)ignore previous|\"q\":\"x\""));
    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .expectErrorMatches(e -> e.getMessage().contains("deny-argument-patterns"))
        .verify();
    assertThat(methods).isEmpty();
  }

  @Test
  void call_withoutArgumentsSkipsTheArgumentChecks() {
    gateway.setMaxArgumentBytes(1);
    final McpCallSpec bare =
        new McpCallSpec(
            spec("ping").url(), "ping", Map.of(), Map.of(), Map.of(), Duration.ofSeconds(5));

    StepVerifier.create(gateway.call(bare, () -> {}))
        .assertNext(r -> assertThat(r.text()).isEqualTo("pong"))
        .verifyComplete();
  }

  @Test
  void call_reusesSession() {
    gateway.call(spec("ping"), () -> {}).block();
    gateway.call(spec("ping"), () -> {}).block();
    assertThat(methods).filteredOn("initialize"::equals).hasSize(1);
  }

  @Test
  void call_reinitializesWhenSessionExpires() {
    gateway.call(spec("ping"), () -> {}).block();
    expireNext.set(1);
    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .assertNext(r -> assertThat(r.text()).isEqualTo("pong"))
        .verifyComplete();
    assertThat(methods).filteredOn("initialize"::equals).hasSize(2);
  }

  @Test
  void call_readsSseAndBeatsOnProgress() {
    sse = true;
    StepVerifier.create(gateway.call(spec("ping"), beats::incrementAndGet))
        .assertNext(r -> assertThat(r.text()).isEqualTo("pong"))
        .verifyComplete();
    assertThat(beats).hasValue(1);
  }

  @Test
  void call_toolErrorIsData() {
    StepVerifier.create(gateway.call(spec("fail"), () -> {}))
        .assertNext(r -> assertThat(r.isError()).isTrue())
        .verifyComplete();
  }

  @Test
  void reset_forcesNewHandshake() {
    gateway.call(spec("ping"), () -> {}).block();
    gateway.reset();
    gateway.call(spec("ping"), () -> {}).block();
    assertThat(methods).filteredOn("initialize"::equals).hasSize(2);
  }

  @Test
  void call_structuredContentIsKept() {
    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .assertNext(r -> assertThat(r.structured()).isEqualTo(Map.of("a", 1)))
        .verifyComplete();
  }

  @Test
  void call_jsonRpcErrorBecomesFailureResult() {
    StepVerifier.create(gateway.call(spec("rpcerror"), () -> {}))
        .assertNext(
            r -> {
              assertThat(r.isError()).isTrue();
              assertThat(r.error()).isEqualTo("nope");
            })
        .verifyComplete();
  }

  @Test
  void call_missingReplyErrors() {
    StepVerifier.create(gateway.call(spec("noreply"), () -> {}))
        .verifyError(McpClientGateway.McpHttpException.class);
  }

  @Test
  void call_unparseableReplyErrors() {
    StepVerifier.create(gateway.call(spec("garbage"), () -> {}))
        .verifyError(McpClientGateway.McpHttpException.class);
  }

  @Test
  void call_httpErrorErrors() {
    StepVerifier.create(gateway.call(spec("http500"), () -> {}))
        .verifyErrorSatisfies(
            e -> assertThat(e).hasMessageContaining("500").hasMessageContaining("kaput"));
  }

  @Test
  void call_failedInitializeErrorsAndIsNotCached() {
    initFail = true;
    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .verifyError(McpClientGateway.McpHttpException.class);
    initFail = false;
    StepVerifier.create(gateway.call(spec("ping"), () -> {})).expectNextCount(1).verifyComplete();
  }

  @Test
  void call_timesOut() {
    final McpCallSpec slow = spec("slow");
    final McpCallSpec quick =
        new McpCallSpec(
            slow.url(),
            slow.tool(),
            slow.arguments(),
            slow.headers(),
            slow.meta(),
            Duration.ofMillis(300));
    StepVerifier.create(gateway.call(quick, () -> {}))
        .verifyError(java.util.concurrent.TimeoutException.class);
  }

  @Test
  void call_cancelTellsServer() throws InterruptedException {
    gateway.call(spec("ping"), () -> {}).block();
    gateway.call(spec("slow"), () -> {}).subscribe().dispose();
    for (int i = 0; i < 50 && !methods.contains("notifications/cancelled"); i++) {
      Thread.sleep(50);
    }
    assertThat(methods).contains("notifications/cancelled");
  }

  @Test
  void call_cancelAfterResetIsQuiet() throws InterruptedException {
    final var subscription = gateway.call(spec("slow"), () -> {}).subscribe();
    gateway.reset();
    subscription.dispose();
    Thread.sleep(100);
    assertThat(methods).doesNotContain("notifications/cancelled");
  }

  @Test
  void close_deletesOpenSessions() {
    gateway.call(spec("ping"), () -> {}).block();
    gateway.close();
    gateway.close();
    gateway.call(spec("ping"), () -> {}).block();
    assertThat(methods).filteredOn("initialize"::equals).hasSize(2);
  }

  @Test
  void spec_sessionKeySeparatesCredentials() {
    final McpCallSpec a = spec("ping");
    final McpCallSpec b =
        new McpCallSpec(
            a.url(), a.tool(), a.arguments(), Map.of("Authorization", "x"), a.meta(), a.timeout());
    assertThat(a.sessionKey()).isNotEqualTo(b.sessionKey());
  }

  @Test
  void statelessServer_noSessionHeaderAnd404IsAnError() {
    stateless = true;
    gateway.call(spec("ping"), () -> {}).block();
    expireNext.set(1);
    StepVerifier.create(gateway.call(spec("ping"), () -> {}))
        .verifyError(McpClientGateway.McpHttpException.class);
    gateway.close();
  }

  private static String rpc(final String id, final String body) {
    return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\"," + body + "}";
  }
}
