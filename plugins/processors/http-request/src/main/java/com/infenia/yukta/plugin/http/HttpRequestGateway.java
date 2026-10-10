// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Gateway executing HTTP requests with WebClient. */
@Service
@RequiredArgsConstructor
public class HttpRequestGateway {

  /** Builder used to create the WebClient for each request. */
  private final WebClient.Builder webClientBuilder;

  /**
   * Execute the request. Non-2xx responses are returned as data; only transport failures and
   * timeouts error.
   *
   * @param spec the resolved request
   * @return a Mono emitting the response
   */
  public Mono<HttpRequestResult> execute(final HttpRequestSpec spec) {
    final WebClient.RequestBodySpec request =
        webClientBuilder
            .build()
            .method(HttpMethod.valueOf(spec.method()))
            .uri(spec.url())
            .headers(headers -> spec.headers().forEach(headers::add));
    final WebClient.RequestHeadersSpec<?> ready =
        spec.body() == null ? request : request.bodyValue(spec.body());
    return ready
        .exchangeToMono(
            response ->
                response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(
                        body ->
                            new HttpRequestResult(
                                response.statusCode().value(),
                                response.headers().asHttpHeaders().toSingleValueMap(),
                                response
                                    .headers()
                                    .contentType()
                                    .filter(type -> type.getSubtype().contains("json"))
                                    .isPresent(),
                                body)))
        .timeout(spec.timeout());
  }
}
