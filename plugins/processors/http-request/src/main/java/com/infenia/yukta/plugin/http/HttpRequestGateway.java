// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

  /** The hosts a request may reach; empty allows every host. */
  private HostAllowList allowedHosts = HostAllowList.from(null);

  /** Where the deployment, workflow and session allow calls to go. */
  private EgressPolicy egress = EgressPolicy.OPEN;

  /**
   * Sets the hosts requests may reach ({@code yukta.http.allowed-hosts}); empty allows any. A
   * listed host is a name, an IP address or {@code *.domain}.
   *
   * @param hosts the allowed hosts, or empty
   */
  @Value("${yukta.http.allowed-hosts:}")
  /* default */ void setAllowedHosts(final List<String> hosts) {
    this.allowedHosts = HostAllowList.from(hosts);
  }

  /**
   * Sets the deployment, workflow and session host lists ({@code yukta.egress.*}).
   *
   * @param policy the policy
   */
  @Autowired(required = false)
  /* default */ void setEgressPolicy(final EgressPolicy policy) {
    this.egress = policy;
  }

  /**
   * Execute the request. Non-2xx responses are returned as data; only transport failures and
   * timeouts error.
   *
   * @param spec the resolved request
   * @return a Mono emitting the response
   */
  public Mono<HttpRequestResult> execute(final HttpRequestSpec spec) {
    return egress.check(spec.url()).then(Mono.defer(() -> send(spec)));
  }

  private Mono<HttpRequestResult> send(final HttpRequestSpec spec) {
    final String refusal = allowedHosts.refusalFor(spec.url(), "yukta.http.allowed-hosts");
    if (refusal != null) {
      return Mono.error(new IllegalArgumentException(refusal));
    }
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
