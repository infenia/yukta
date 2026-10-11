// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
}
