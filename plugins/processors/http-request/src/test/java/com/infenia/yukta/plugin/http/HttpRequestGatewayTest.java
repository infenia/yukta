// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@NoArgsConstructor
class HttpRequestGatewayTest {

  private HttpServer server;
  private String base;
  private final HttpRequestGateway gateway = new HttpRequestGateway(WebClient.builder());

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/echo",
        exchange -> {
          final byte[] requestBody = exchange.getRequestBody().readAllBytes();
          final String reply =
              exchange.getRequestMethod()
                  + "|"
                  + exchange.getRequestHeaders().getFirst("X-T")
                  + "|"
                  + new String(requestBody, StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(201, reply.length());
          exchange.getResponseBody().write(reply.getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    server.createContext(
        "/empty",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.createContext(
        "/slow",
        exchange -> {
          try {
            // a slow server is the input of the timeout test
            Thread.sleep(1500); // nosem: yukta-test-thread-sleep
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    base = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsMethodHeadersAndBody() {
    final HttpRequestSpec spec =
        new HttpRequestSpec(
            "POST", base + "/echo", Map.of("X-T", "t"), "hello", Duration.ofSeconds(5));

    StepVerifier.create(gateway.execute(spec))
        .assertNext(
            r -> {
              assertThat(r.status()).isEqualTo(201);
              assertThat(r.isSuccess()).isTrue();
              assertThat(r.json()).isTrue();
              assertThat(r.body()).isEqualTo("POST|t|hello");
              assertThat(r.headers().keySet()).anyMatch("content-type"::equalsIgnoreCase);
            })
        .verifyComplete();
  }

  @Test
  void hostsOutsideTheAllowListAreRefusedWithoutCalling() {
    gateway.setAllowedHosts(List.of("example.com"));
    final HttpRequestSpec spec =
        new HttpRequestSpec("GET", base + "/echo", Map.of(), null, Duration.ofSeconds(5));

    StepVerifier.create(gateway.execute(spec))
        .expectErrorMessage("Host 'localhost' is not in yukta.http.allowed-hosts")
        .verify();
  }

  @Test
  void workflowEgressListRefusesBeforeCalling() {
    gateway.setEgressPolicy(
        new EgressPolicy(
            HostAllowList.from(null),
            Map.of("wf", HostAllowList.from(List.of("example.com"))),
            Map.of()));
    final HttpRequestSpec spec =
        new HttpRequestSpec("GET", base + "/echo", Map.of(), null, Duration.ofSeconds(5));

    StepVerifier.create(gateway.execute(spec).contextWrite(Context.of("workflowId", "wf")))
        .expectErrorMessage("Host 'localhost' is not in yukta.egress.by-workflow.wf")
        .verify();
  }

  @Test
  void listedHostIsCalled() {
    gateway.setAllowedHosts(List.of("localhost"));
    final HttpRequestSpec spec =
        new HttpRequestSpec("GET", base + "/echo", Map.of(), null, Duration.ofSeconds(5));

    StepVerifier.create(gateway.execute(spec))
        .assertNext(r -> assertThat(r.status()).isEqualTo(201))
        .verifyComplete();
  }

  @Test
  void nonSuccessStatusAndEmptyBodyAreData() {
    final HttpRequestSpec spec =
        new HttpRequestSpec("GET", base + "/empty", Map.of(), null, Duration.ofSeconds(5));

    StepVerifier.create(gateway.execute(spec))
        .assertNext(
            r -> {
              assertThat(r.status()).isEqualTo(500);
              assertThat(r.isSuccess()).isFalse();
              assertThat(r.json()).isFalse();
              assertThat(r.body()).isEmpty();
            })
        .verifyComplete();
  }

  @Test
  void timesOut() {
    final HttpRequestSpec spec =
        new HttpRequestSpec("GET", base + "/slow", Map.of(), null, Duration.ofMillis(200));

    StepVerifier.create(gateway.execute(spec)).expectError().verify();
  }

  @Test
  void transportFailureResultHasStatusZero() {
    final HttpRequestResult result = HttpRequestResult.transportFailure("x");
    assertThat(result.status()).isZero();
    assertThat(result.isSuccess()).isFalse();
  }
}
