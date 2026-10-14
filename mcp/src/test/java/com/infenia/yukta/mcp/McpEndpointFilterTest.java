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
  void testNonAdminKeyIsRefused() {
    final var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("yukta.api-keys[0].key", "v")
            .withProperty("yukta.api-keys[0].role", "OPERATOR");
    final var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").header("X-API-Key", "v"));
    new McpEndpointFilter(env).filter(exchange, (ServerWebExchange e) -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
  void testSessionLimitedKeyIsRefused() {
    final var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("yukta.api-keys[0].key", "k")
            .withProperty("yukta.api-keys[0].role", "ADMIN")
            .withProperty("yukta.api-keys[0].sessions", "a");
    final var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").header("X-API-Key", "k"));
    new McpEndpointFilter(env).filter(exchange, (ServerWebExchange e) -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
}
