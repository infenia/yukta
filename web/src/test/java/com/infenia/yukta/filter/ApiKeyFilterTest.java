// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link ApiKeyFilter}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidDuplicateLiterals", "PMD.ShortVariable"})
class ApiKeyFilterTest {

  /** The passed. */
  private final AtomicBoolean passed = new AtomicBoolean();

  private MockServerWebExchange run(
      final String key, final MockServerHttpRequest.BaseBuilder<?> rq) {
    passed.set(false);
    final MockServerWebExchange exchange = MockServerWebExchange.from(rq);
    new ApiKeyFilter(key)
        .filter(
            exchange,
            _ -> {
              passed.set(true);
              return Mono.empty();
            })
        .block();
    return exchange;
  }

  @Test
  void testNoKeyConfiguredLeavesApiOpen() {
    run("", MockServerHttpRequest.get("/api/sessions"));

    assertThat(passed).isTrue();
  }

  @Test
  void testMissingOrWrongKeyIsRejected() {
    final var missing = run("secret", MockServerHttpRequest.get("/api/sessions"));
    assertThat(missing.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(passed).isFalse();

    final var wrong =
        run("secret", MockServerHttpRequest.get("/api/sessions").header("X-API-Key", "nope"));
    assertThat(wrong.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testBearerAndHeaderKeysAccepted() {
    run("secret", MockServerHttpRequest.get("/api/x").header("Authorization", "Bearer secret"));
    assertThat(passed).isTrue();

    run("secret", MockServerHttpRequest.get("/api/x").header("X-API-Key", "secret"));
    assertThat(passed).isTrue();
  }

  @Test
  void testNonApiPathsAreNotGuarded() {
    run("secret", MockServerHttpRequest.get("/actuator/health"));

    assertThat(passed).isTrue();
  }

  @Test
  void testNonBearerAuthorizationFallsBackToTheApiKeyHeader() {
    run(
        "secret",
        MockServerHttpRequest.get("/api/sessions")
            .header("Authorization", "Basic abc")
            .header("X-API-Key", "secret"));

    assertThat(passed).isTrue();
  }
}
