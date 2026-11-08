// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.supervisor.ControlReviewer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** A person's control command names the supervisor that carried it. */
class ControlViaFilterTest {

  private final ControlViaFilter filter = new ControlViaFilter();

  @Test
  void theCarrierTheReviewerNamesIsTheHeader() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/executions/e/pause"));
    filter
        .filter(
            exchange,
            ex ->
                Mono.deferContextual(
                    ctx -> {
                      ctx.<AtomicReference<String>>get(ControlReviewer.VIA_KEY).set("guard");
                      return ex.getResponse().setComplete();
                    }))
        .block();
    assertThat(exchange.getResponse().getHeaders().getFirst(ControlViaFilter.HEADER))
        .isEqualTo("guard");
  }

  @Test
  void nothingReviewedAndReadsCarryNoHeader() {
    final MockServerWebExchange post =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/sessions"));
    filter.filter(post, ex -> ex.getResponse().setComplete()).block();
    assertThat(post.getResponse().getHeaders().getFirst(ControlViaFilter.HEADER)).isNull();

    final MockServerWebExchange get =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/me"));
    filter
        .filter(
            get,
            ex ->
                Mono.deferContextual(
                    ctx -> {
                      assertThat(ctx.hasKey(ControlReviewer.VIA_KEY)).isFalse();
                      return ex.getResponse().setComplete();
                    }))
        .block();
    assertThat(get.getResponse().getHeaders().getFirst(ControlViaFilter.HEADER)).isNull();
  }
}
