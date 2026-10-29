// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Tests for SecurityHeadersFilter. */
@NoArgsConstructor
class SecurityHeadersFilterTest {

  private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

  private MockServerWebExchange run(final MockServerHttpRequest.BaseBuilder<?> request) {
    final MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> e.getResponse().setComplete()).block();
    return exchange;
  }

  @Test
  void plainHttpGetsNosniffAndReferrerPolicyButNoHsts() {
    final var headers =
        run(MockServerHttpRequest.get("http://localhost/api/x")).getResponse().getHeaders();

    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headers.containsHeader("Strict-Transport-Security")).isFalse();
  }

  @Test
  void httpsRequestGetsHsts() {
    final var headers =
        run(MockServerHttpRequest.get("https://yukta.example/api/x")).getResponse().getHeaders();

    assertThat(headers.getFirst("Strict-Transport-Security")).isEqualTo("max-age=31536000");
  }

  @Test
  void forwardedHttpsGetsHsts() {
    final var headers =
        run(MockServerHttpRequest.get("http://yukta.internal/api/x")
                .header("X-Forwarded-Proto", "https"))
            .getResponse()
            .getHeaders();

    assertThat(headers.getFirst("Strict-Transport-Security")).isNotNull();
  }
}
