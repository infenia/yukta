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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestTracingFilterTest {

  private final RequestTracingFilter filter = new RequestTracingFilter();

  @Test
  void testConstructor() {
    RequestTracingFilter instance = new RequestTracingFilter();
    assertThat(instance).isNotNull();
  }

  @Test
  void testFilterGeneratesNewRequestIdWhenMissing() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    HttpHeaders requestHeaders = new HttpHeaders();
    HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.get("X-Request-ID")).isNotNull();
    assertThat(responseHeaders.get("X-Request-ID")).hasSize(1);
    assertThat(responseHeaders.get("X-Correlation-ID")).isNotNull();
    assertThat(responseHeaders.get("X-Correlation-ID")).hasSize(1);
  }

  @Test
  void testFilterUsesExistingRequestId() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.add("X-Request-ID", "existing-request-id");
    HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.getFirst("X-Request-ID")).isEqualTo("existing-request-id");
    assertThat(responseHeaders.getFirst("X-Correlation-ID")).isEqualTo("existing-request-id");
  }

  @Test
  void testFilterUsesExistingCorrelationId() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.add("X-Correlation-ID", "existing-correlation-id");
    HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.getFirst("X-Request-ID")).isNotNull();
    assertThat(responseHeaders.getFirst("X-Correlation-ID")).isEqualTo("existing-correlation-id");
  }

  @Test
  void testFilterUsesBothExistingHeaders() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.add("X-Request-ID", "existing-request-id");
    requestHeaders.add("X-Correlation-ID", "existing-correlation-id");
    HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.getFirst("X-Request-ID")).isEqualTo("existing-request-id");
    assertThat(responseHeaders.getFirst("X-Correlation-ID")).isEqualTo("existing-correlation-id");
  }

  @Test
  void testFilterContinuesChain() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    HttpHeaders requestHeaders = new HttpHeaders();
    HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.error(new RuntimeException("test error")));

    StepVerifier.create(filter.filter(exchange, chain))
        .expectError(RuntimeException.class)
        .verify();

    verify(chain).filter(exchange);
  }
}
