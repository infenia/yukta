// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link HostOriginFilter}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidDuplicateLiterals"})
class HostOriginFilterTest {

  /** Whether the request got through. */
  private final AtomicBoolean passed = new AtomicBoolean();

  private HttpStatus run(
      final MockEnvironment environment, final MockServerHttpRequest.BaseBuilder<?> request) {
    passed.set(false);
    final var exchange = MockServerWebExchange.from(request);
    new HostOriginFilter(environment)
        .filter(
            exchange,
            _ -> {
              passed.set(true);
              return Mono.empty();
            })
        .block();
    return (HttpStatus) exchange.getResponse().getStatusCode();
  }

  @Test
  void testLoopbackHostAndSameOriginPass() {
    run(
        new MockEnvironment(),
        MockServerHttpRequest.post("/api/workflow/start")
            .header("Host", "localhost:8080")
            .header("Origin", "http://localhost:8080"));

    assertThat(passed).isTrue();
  }

  @Test
  void testRebindingHostIsRefused() {
    assertThat(
            run(
                new MockEnvironment(),
                MockServerHttpRequest.get("/api/events/stream")
                    .header("Host", "evil.example:8080")))
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(passed).isFalse();
  }

  @Test
  void testForeignOriginCannotChangeAnything() {
    assertThat(
            run(
                new MockEnvironment(),
                MockServerHttpRequest.post("/api/scheduler/limits")
                    .header("Host", "localhost:8080")
                    .header("Origin", "https://evil.example")))
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(passed).isFalse();
  }

  @Test
  void testConfiguredHostAndOriginPass() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.allowed-hosts", "yukta.internal")
            .withProperty("yukta.allowed-origins", "http://ui.internal:5173");

    run(
        env,
        MockServerHttpRequest.post("/api/workflow/start")
            .header("Host", "yukta.internal")
            .header("Origin", "http://ui.internal:5173"));

    assertThat(passed).isTrue();
  }

  @Test
  void testOffWhenKeyIsConfigured() {
    run(
        new MockEnvironment().withProperty("yukta.api-key", "k"),
        MockServerHttpRequest.post("/api/workflow/start")
            .header("Host", "anything")
            .header("Origin", "https://evil.example"));

    assertThat(passed).isTrue();
  }

  @Test
  void testStillOnWhenOnlyTheMcpKeyIsConfigured() {
    final var status =
        run(
            new MockEnvironment().withProperty("yukta.mcp.api-key", "k"),
            MockServerHttpRequest.post("/api/workflow/start")
                .header("Host", "rebound.example")
                .header("Origin", "https://evil.example"));

    assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(passed).isFalse();
  }

  @Test
  void testReadsAndIpv6HostsAndMissingHostPass() {
    run(new MockEnvironment(), MockServerHttpRequest.get("/api/x").header("Host", "[::1]:8080"));
    assertThat(passed).isTrue();
    run(
        new MockEnvironment(),
        MockServerHttpRequest.head("/api/x")
            .header("Host", "localhost")
            .header("Origin", "https://evil.example"));
    assertThat(passed).isTrue();
    run(
        new MockEnvironment(),
        MockServerHttpRequest.options("/api/x")
            .header("Host", "localhost")
            .header("Origin", "https://evil.example"));
    assertThat(passed).isTrue();
    run(new MockEnvironment(), MockServerHttpRequest.get("/api/x"));
    assertThat(passed).isTrue();
  }

  @Test
  void testMissingHostCannotPostFromForeignOrNullOrigin() {
    final MockEnvironment environment = new MockEnvironment();
    for (final String origin : new String[] {"https://evil.example", "null", "http://[bad"}) {
      assertThat(run(environment, MockServerHttpRequest.post("/api/x").header("Origin", origin)))
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void testPostWithoutOriginPasses() {
    run(
        new MockEnvironment(),
        MockServerHttpRequest.post("/api/x").header("Host", "localhost:8080"));

    assertThat(passed).isTrue();
  }

  @Test
  void testConfiguredHostWithPortPasses() {
    run(
        new MockEnvironment().withProperty("yukta.allowed-hosts", "yukta.internal:8443"),
        MockServerHttpRequest.get("/api/x").header("Host", "yukta.internal:8443"));
    assertThat(passed).isTrue();
  }
}
