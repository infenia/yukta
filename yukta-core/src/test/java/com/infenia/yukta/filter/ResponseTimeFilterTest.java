/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResponseTimeFilterTest {

  @Test
  @SuppressWarnings("unchecked")
  void testFilter() {
    ResponseTimeFilter filter = new ResponseTimeFilter();
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    WebFilterChain chain = mock(WebFilterChain.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    HttpHeaders headers = new HttpHeaders();

    when(exchange.getResponse()).thenReturn(response);
    when(response.getHeaders()).thenReturn(headers);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    // Use doAnswer for void method
    doAnswer(
            inv -> {
              Supplier<Mono<Void>> supplier = inv.getArgument(0);
              supplier.get(); // Trigger the header addition
              return null;
            })
        .when(response)
        .beforeCommit(any());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assert headers.get("X-Response-Time") != null;
    verify(chain).filter(exchange);
  }
}
