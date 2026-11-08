// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import com.infenia.yukta.service.supervisor.ControlReviewer;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Says on the response of a person's control command which supervisor carried it: header {@value
 * #HEADER} with the owner's id, or {@code default} where nobody owns the unit. Set for every
 * command the owner reviewed, also for a refusal; absent on reads and on anything not reviewed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ControlViaFilter implements WebFilter {

  /** The response header that names the supervisor that carried the command. */
  public static final String HEADER = "Yukta-Via";

  /** Default constructor for Spring. */
  public ControlViaFilter() {
    // Intentionally empty
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    if (HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
      return chain.filter(exchange);
    }
    final AtomicReference<String> via = new AtomicReference<>();
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              final String carrier = via.get();
              if (carrier != null) {
                exchange.getResponse().getHeaders().set(HEADER, carrier);
              }
              return Mono.empty();
            });
    return chain.filter(exchange).contextWrite(ctx -> ctx.put(ControlReviewer.VIA_KEY, via));
  }
}
