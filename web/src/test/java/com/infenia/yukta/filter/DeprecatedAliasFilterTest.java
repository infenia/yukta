// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Tests for DeprecatedAliasFilter. */
@NoArgsConstructor
class DeprecatedAliasFilterTest {

  private final DeprecatedAliasFilter filter = new DeprecatedAliasFilter();

  private String deprecation(final String path) {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post(path));
    filter.filter(exchange, e -> e.getResponse().setComplete()).block();
    return exchange.getResponse().getHeaders().getFirst("Deprecation");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/workflow/s/status/e",
        "/api/workflow/s/status/e/stream",
        "/api/workflow/executions/e/stop",
        "/api/workflow/executions/e/restart/n",
        "/api/workflow/s/e/pause",
        "/api/workflow/s/e/resume",
        "/api/workflow/s/e/recover",
        "/api/workflow/s/e/node/n/stop"
      })
  void aliasIsMarked(final String path) {
    assertThat(deprecation(path)).isEqualTo("true");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/workflow/start",
        "/api/workflow/s/w/stop",
        "/api/workflow/s/history",
        "/api/executions/e/stop"
      })
  void currentRouteIsNotMarked(final String path) {
    assertThat(deprecation(path)).isNull();
  }

  @Test
  void aliasRequestStillReachesTheChain() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/workflow/s/status/e"));
    final boolean[] reached = {false};
    filter
        .filter(
            exchange,
            e -> {
              reached[0] = true;
              return e.getResponse().setComplete();
            })
        .block();
    assertThat(reached[0]).isTrue();
  }
}
