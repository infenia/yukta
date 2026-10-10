// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** Tests for the behaviour described by {@link RequestTracingFilter}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.ShortVariable"})
class RequestTracingFilterTest {

  /** The x r e q u e s t i d. */
  private static final String X_REQUEST_ID = "X-Request-ID";

  /** The x c o r r e l a t i o n i d. */
  private static final String X_CORRELATION_ID = "X-Correlation-ID";

  /** The filter. */
  private final RequestTracingFilter filter = new RequestTracingFilter();

  /** The seen. */
  private final AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

  /** The context id. */
  private final AtomicReference<String> contextId = new AtomicReference<>();

  private MockServerWebExchange run(final MockServerHttpRequest.BaseBuilder<?> request) {
    final MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter
        .filter(
            exchange,
            e -> {
              seen.set(e);
              return Mono.deferContextual(
                  ctx -> {
                    contextId.set(ctx.getOrDefault(RequestTracingFilter.REQUEST_ID_KEY, null));
                    return Mono.empty();
                  });
            })
        .block();
    return exchange;
  }

  @Test
  void testGeneratesIdWhenMissingAndPropagatesItEverywhere() {
    final var exchange = run(MockServerHttpRequest.get("/api/x"));

    final String id = exchange.getResponse().getHeaders().getFirst(X_REQUEST_ID);
    assertThat(id).isNotBlank();
    assertThat(exchange.getResponse().getHeaders().getFirst(X_CORRELATION_ID)).isEqualTo(id);
    assertThat(seen.get().getRequest().getHeaders().getFirst(X_REQUEST_ID)).isEqualTo(id);
    assertThat(contextId.get()).isEqualTo(id);
  }

  @Test
  void testKeepsSafeClientSuppliedIds() {
    final var exchange =
        run(
            MockServerHttpRequest.get("/api/x")
                .header(X_REQUEST_ID, "req-1")
                .header(X_CORRELATION_ID, "corr-1"));

    assertThat(exchange.getResponse().getHeaders().getFirst(X_REQUEST_ID)).isEqualTo("req-1");
    assertThat(exchange.getResponse().getHeaders().getFirst(X_CORRELATION_ID)).isEqualTo("corr-1");
    assertThat(contextId.get()).isEqualTo("req-1");
  }

  @Test
  void testReplacesUnsafeClientSuppliedIds() {
    final var exchange =
        run(
            MockServerHttpRequest.get("/api/x")
                .header(X_REQUEST_ID, "bad id\twith spaces and a very long tail ".repeat(5))
                .header(X_CORRELATION_ID, "<script>"));

    final String id = exchange.getResponse().getHeaders().getFirst(X_REQUEST_ID);
    assertThat(id).matches("[A-Za-z0-9-]{1,64}");
    assertThat(exchange.getResponse().getHeaders().getFirst(X_CORRELATION_ID)).isEqualTo(id);
  }

  @Test
  void testMdcIsRegisteredAsContextAccessorKey() {
    MDC.put(RequestTracingFilter.REQUEST_ID_KEY, "x");
    assertThat(MDC.get(RequestTracingFilter.REQUEST_ID_KEY)).isEqualTo("x");
    MDC.remove(RequestTracingFilter.REQUEST_ID_KEY);
  }

  @Test
  @SuppressWarnings("PMD.UnusedLocalVariable")
  void testRequestIdIsRestoredIntoMdcFromReactorContext() {
    try (var scope =
        ContextSnapshotFactory.builder()
            .build()
            .setThreadLocalsFrom(Context.of(RequestTracingFilter.REQUEST_ID_KEY, "abc-1"))) {
      assertThat(MDC.get(RequestTracingFilter.REQUEST_ID_KEY)).isEqualTo("abc-1");
    }
    assertThat(MDC.get(RequestTracingFilter.REQUEST_ID_KEY)).isNull();
  }
}
