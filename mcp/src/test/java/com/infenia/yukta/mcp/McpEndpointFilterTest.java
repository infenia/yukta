// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class McpEndpointFilterTest {

  private final AtomicReference<String> seenPath = new AtomicReference<>();

  private MockServerWebExchange run(
      final String key, final MockServerHttpRequest.BaseBuilder<?> rq) {
    final MockServerWebExchange exchange = MockServerWebExchange.from(rq);
    new McpEndpointFilter(key)
        .filter(
            exchange,
            (ServerWebExchange e) -> {
              seenPath.set(e.getRequest().getPath().value());
              return Mono.empty();
            })
        .block();
    return exchange;
  }

  @Test
  void testLegacySsePathIsRewrittenToMcp() {
    run("", MockServerHttpRequest.post("/sse"));

    assertThat(seenPath.get()).isEqualTo("/mcp");
  }

  @Test
  void testOtherPathsUntouchedAndUnguarded() {
    final var exchange = run("secret", MockServerHttpRequest.get("/api/sessions"));

    assertThat(seenPath.get()).isEqualTo("/api/sessions");
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void testMissingKeyRejectedWhenConfigured() {
    final var exchange = run("secret", MockServerHttpRequest.post("/mcp"));

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(seenPath.get()).isNull();
  }

  @Test
  void testWrongKeyRejectedOnLegacyPathToo() {
    final var exchange =
        run("secret", MockServerHttpRequest.post("/sse").header("X-API-Key", "nope"));

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testBearerAndHeaderKeysAccepted() {
    run("secret", MockServerHttpRequest.post("/mcp").header("Authorization", "Bearer secret"));
    assertThat(seenPath.get()).isEqualTo("/mcp");

    seenPath.set(null);
    run("secret", MockServerHttpRequest.post("/mcp").header("X-API-Key", "secret"));
    assertThat(seenPath.get()).isEqualTo("/mcp");
  }

  @Test
  void testNonBearerAuthorizationFallsBackToTheApiKeyHeader() {
    run(
        "secret",
        MockServerHttpRequest.post("/mcp")
            .header("Authorization", "Basic abc")
            .header("X-API-Key", "secret"));

    assertThat(seenPath.get()).isEqualTo("/mcp");
  }

  @Test
  void testDifferentlyWrittenPathsAreRefused() {
    for (final String path : new String[] {"/mcp;x=1", "/%6dcp", "/./mcp", "/mcp/"}) {
      seenPath.set(null);
      final var exchange =
          run("secret", MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)));
      assertThat(exchange.getResponse().getStatusCode())
          .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);
      assertThat(seenPath.get()).isNull();
    }
  }

  @Test
  void testRestKeyOpensMcpToo() {
    final var env =
        new org.springframework.mock.env.MockEnvironment().withProperty("yukta.api-key", "rest");
    final var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp"));
    new McpEndpointFilter(env).filter(exchange, (ServerWebExchange e) -> Mono.empty()).block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    seenPath.set(null);
    final var ok =
        MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").header("X-API-Key", "rest"));
    new McpEndpointFilter(env)
        .filter(
            ok,
            (ServerWebExchange e) -> {
              seenPath.set("passed");
              return Mono.empty();
            })
        .block();
    assertThat(seenPath.get()).isEqualTo("passed");
  }

  @Test
  void testNamedKeySetsTheActor() {
    final var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "bot")
            .withProperty("yukta.api-keys[0].key", "k")
            .withProperty("yukta.api-keys[0].role", "ADMIN");
    final var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/sse").header("X-API-Key", "k"));
    final var actor = new AtomicReference<String>();
    new McpEndpointFilter(env)
        .filter(
            exchange,
            (ServerWebExchange e) ->
                Mono.deferContextual(
                    ctx -> {
                      actor.set(
                          ctx.getOrDefault(
                              com.infenia.yukta.plugin.control.ControlActor.CONTEXT_KEY, null));
                      seenPath.set(e.getRequest().getPath().value());
                      return Mono.empty();
                    }))
        .block();

    assertThat(actor.get()).isEqualTo("key:bot");
    assertThat(seenPath.get()).isEqualTo("/mcp");
  }

  @Test
  void testAmbiguousPathIsRefusedBeforeKeyCheck() {
    final var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, URI.create("/mcp;x=1"))
                .header("X-API-Key", "secret"));
    new McpEndpointFilter("secret").filter(exchange, (ServerWebExchange e) -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private static final String START =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"start_workflow\",\"arguments\":{\"sessionId\":\"%s\"}}}";

  private static org.springframework.mock.env.MockEnvironment limited(
      final String role, final String sessions) {
    final var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("yukta.api-keys[0].key", "k")
            .withProperty("yukta.api-keys[0].role", role);
    return sessions == null ? env : env.withProperty("yukta.api-keys[0].sessions", sessions);
  }

  private String call(
      final org.springframework.mock.env.MockEnvironment env,
      final String path,
      final String body) {
    final var request = MockServerHttpRequest.post(path).header("X-API-Key", "k");
    final var exchange =
        MockServerWebExchange.from(body == null ? request.build() : request.body(body));
    final var seenBody = new AtomicReference<String>();
    new McpEndpointFilter(env)
        .filter(
            exchange,
            (ServerWebExchange e) ->
                e.getRequest()
                    .getBody()
                    .map(
                        buffer -> {
                          final byte[] bytes = new byte[buffer.readableByteCount()];
                          buffer.read(bytes);
                          return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        })
                    .collectList()
                    .doOnNext(parts -> seenBody.set(String.join("", parts)))
                    .then(Mono.fromRunnable(() -> seenPath.set(e.getRequest().getPath().value()))))
        .block();
    final var status = exchange.getResponse().getStatusCode();
    return status == null ? "passed:" + seenBody.get() : status.toString();
  }

  @Test
  void testOperatorMayStartButNotCreateSessions() {
    final var env = limited("OPERATOR", null);

    assertThat(call(env, "/mcp", START.formatted("a")))
        .startsWith("passed:")
        .contains("start_workflow");
    assertThat(
            call(
                env,
                "/mcp",
                "{\"method\":\"tools/call\",\"params\":{\"name\":\"create_session\"}}"))
        .startsWith("403");
  }

  @Test
  void testViewerMayReadButNotControl() {
    final var env = limited("VIEWER", null);

    assertThat(call(env, "/mcp", START.formatted("a"))).startsWith("403");
    assertThat(
            call(
                env, "/mcp", "{\"method\":\"tools/call\",\"params\":{\"name\":\"list_sessions\"}}"))
        .startsWith("passed:");
    assertThat(call(env, "/mcp", "{\"method\":\"tools/list\"}")).startsWith("passed:");
  }

  @Test
  void testSessionLimitedKeyReachesOnlyItsSessions() {
    final var env = limited("ADMIN", "a");

    assertThat(call(env, "/mcp", START.formatted("a"))).startsWith("passed:");
    assertThat(call(env, "/mcp", START.formatted("b"))).startsWith("403");
    assertThat(
            call(
                env, "/mcp", "{\"method\":\"tools/call\",\"params\":{\"name\":\"list_sessions\"}}"))
        .startsWith("403");
    assertThat(
            call(env, "/mcp", "{\"method\":\"tools/call\",\"params\":{\"name\":\"list_plugins\"}}"))
        .startsWith("passed:");
  }

  @Test
  void testBatchesInvalidJsonEmptyBodiesAndLegacyPathAreHandled() {
    final var env = limited("OPERATOR", null);
    final String ok = START.formatted("a");
    final String bad = "{\"method\":\"tools/call\",\"params\":{\"name\":\"create_session\"}}";

    assertThat(call(env, "/mcp", "[" + ok + "," + ok + "]")).startsWith("passed:");
    assertThat(call(env, "/mcp", "[" + ok + "," + bad + "]")).startsWith("403");
    assertThat(call(env, "/mcp", "not json")).startsWith("403");
    assertThat(call(env, "/mcp", null)).startsWith("passed:");
    assertThat(call(env, "/sse", ok)).startsWith("passed:");
    assertThat(seenPath.get()).isEqualTo("/mcp");
  }

  @Test
  void testLimitedKeysMayOpenStreamWithoutBody() {
    final var env = limited("VIEWER", null);
    final var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/mcp").header("X-API-Key", "k"));
    new McpEndpointFilter(env)
        .filter(
            exchange, (ServerWebExchange e) -> Mono.fromRunnable(() -> seenPath.set("streamed")))
        .block();

    assertThat(seenPath.get()).isEqualTo("streamed");
  }

  @Test
  void testBodyOverOneMebibyteIsRefusedWith413() {
    final var env = limited("OPERATOR", null);

    assertThat(call(env, "/mcp", "x".repeat((1 << 20) + 1))).startsWith("413");
  }
}
