// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/** Posting the signals of an {@code http} supervisor to the external system, on a local server. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.SignatureDeclareThrowsException",
  "PMD.AvoidUsingHardCodedIP"
})
class OutboundTest {

  private static final String SECRET = "s3cr3t-value";

  private static final int REDIRECT = 302;

  private static final Signal TICK = new Signal.Tick(Instant.EPOCH);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Egress ALLOW_LOOPBACK =
      new Egress(EgressPolicy.OPEN, HostAllowList.from(List.of("127.0.0.1")));

  private static final Egress CLOSED_EGRESS =
      new Egress(EgressPolicy.OPEN, HostAllowList.from(null));

  private static final Outbound OPEN =
      new Outbound(
          ALLOW_LOOPBACK, WebClient.create(), JSON, name -> "TOKEN".equals(name) ? SECRET : null);

  private static final Outbound CLOSED =
      new Outbound(
          CLOSED_EGRESS, WebClient.create(), JSON, name -> "TOKEN".equals(name) ? SECRET : null);

  private HttpServer server;

  private Outbound.Config config;

  private final AtomicInteger status = new AtomicInteger(200);

  private final List<String> bodies = new CopyOnWriteArrayList<>();

  private final List<String> secrets = new CopyOnWriteArrayList<>();

  private final List<String> paths = new CopyOnWriteArrayList<>();

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          secrets.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Secret")));
          if (status.get() == REDIRECT) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
          }
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    logs.start();
    ((Logger) LoggerFactory.getLogger(Outbound.class)).addAppender(logs);
    final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    config = OPEN.read(Map.of("url", url, "secretHeaders", Map.of("X-Secret", "${env.TOKEN}")));
  }

  @AfterEach
  void shutDown() {
    server.stop(0);
    ((Logger) LoggerFactory.getLogger(Outbound.class)).detachAppender(logs);
  }

  private void deliver(final Outbound outbound, final AgentHub.Sequenced... signals) {
    outbound.deliver("ext", config, Flux.just(signals)).block(Duration.ofSeconds(15));
  }

  @Test
  void eachSignalIsPostedAsTheAgentApiShowsItWithTheHeaders() throws Exception {
    deliver(OPEN, new AgentHub.Sequenced(1, TICK), new AgentHub.Sequenced(2, TICK));

    assertThat(bodies).hasSize(2);
    final Map<?, ?> first = JSON.readValue(bodies.getFirst(), Map.class);
    assertThat(List.<Object>copyOf(first.keySet())).containsExactly("cursor", "kind", "signal");
    assertThat(first.get("kind")).isEqualTo("TICK");
    assertThat(bodies).anySatisfy(body -> assertThat(body).contains("\"cursor\":2"));
    assertThat(secrets).containsOnly(SECRET);
  }

  @Test
  void failedPostIsLoggedWithoutSecretsAndDeliveryGoesOn() {
    status.set(500);

    deliver(OPEN, new AgentHub.Sequenced(1, TICK), new AgentHub.Sequenced(2, TICK));

    assertThat(bodies).hasSize(2);
    assertThat(logs.list).hasSize(2);
    for (final ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
      assertThat(String.valueOf(event.getKeyValuePairs())).doesNotContain(SECRET);
    }
    assertThat(config.toString()).doesNotContain(SECRET).contains("X-Secret");
  }

  @Test
  void redirectsAreNotFollowed() {
    status.set(REDIRECT);

    deliver(OPEN, new AgentHub.Sequenced(1, TICK));

    assertThat(paths).containsExactly("/hook");
  }

  @Test
  void loopbackTargetIsRefusedUnlessTheListNamesIt() {
    deliver(CLOSED, new AgentHub.Sequenced(1, TICK));

    assertThat(bodies).isEmpty();
    assertThat(logs.list).hasSize(1);
  }

  @Test
  void settingsAreReadAndRefusedWithoutRepeatingValues() {
    assertThat(OPEN.read(Map.of())).isNull();
    assertThat(OPEN.read(Map.of("url", " "))).isNull();
    final Outbound.Config read =
        OPEN.read(
            Map.of(
                "url",
                "https://x.example/in",
                "headers",
                Map.of("A", "b"),
                "timeoutMs",
                500,
                "maxConcurrent",
                2));
    assertThat(read.headers()).containsEntry("A", "b");
    assertThat(read.timeout()).isEqualTo(Duration.ofMillis(500));
    assertThat(read.maxConcurrent()).isEqualTo(2);
    final Outbound.Config plain =
        OPEN.read(Map.of("url", "https://x.example", "headers", Map.of("A", "${env.TOKEN")));
    assertThat(plain.headers()).containsEntry("A", "${env.TOKEN");
    assertThat(OPEN.read(Map.of("url", "https://x.example")).timeout())
        .isEqualTo(Duration.ofSeconds(3));

    assertThatThrownBy(() -> OPEN.read(Map.of("url", "ftp://x.example")))
        .hasMessageContaining("http or https");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https:///x")))
        .hasMessageContaining("with a host");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https://x.example", "headers", "A: b")))
        .hasMessageContaining("headers must be an object");
    assertThatThrownBy(
            () ->
                OPEN.read(
                    Map.of(
                        "url",
                        "https://x.example",
                        "secretHeaders",
                        Map.of("X", "${env.MISSING}"))))
        .hasMessageContaining("MISSING")
        .hasMessageContaining("secretHeaders.X");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https://x.example", "timeoutMs", 0)))
        .hasMessageContaining("timeoutMs must be a whole number from 1 to 10000");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https://x.example", "timeoutMs", 1.5)))
        .hasMessageContaining("timeoutMs");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https://x.example", "maxConcurrent", "many")))
        .hasMessageContaining("maxConcurrent");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", 42))).hasMessageContaining("http or https");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https://x.example", "timeoutMs", 10_001)))
        .hasMessageContaining("timeoutMs must be a whole number from 1 to 10000");
    assertThatThrownBy(() -> OPEN.read(Map.of("url", "https://x.example", "maxConcurrent", 33)))
        .hasMessageContaining("maxConcurrent must be a whole number from 1 to 32");
  }

  @Test
  void addressesInsideTheNetworkAreTold() throws Exception {
    for (final String inside :
        List.of(
            "127.0.0.1",
            "10.1.2.3",
            "192.168.0.9",
            "172.16.0.1",
            "169.254.1.1",
            "0.0.0.0",
            "100.64.0.1",
            "::1",
            "fd00::1",
            "fe80::1")) {
      assertThat(Egress.inside(InetAddress.getByName(inside))).as(inside).isTrue();
    }
    for (final String outside : List.of("8.8.8.8", "100.128.0.1", "2606:4700::1111")) {
      assertThat(Egress.inside(InetAddress.getByName(outside))).as(outside).isFalse();
    }
  }

  @Test
  void privateRefusalReadsTheHostAndTheList() throws Exception {
    assertThat(CLOSED_EGRESS.privateRefusal("http:///x")).contains("no host");
    assertThat(CLOSED_EGRESS.privateRefusal("http://8.8.8.8/x")).isNull();
    assertThat(CLOSED_EGRESS.privateRefusal("http://10.0.0.5/x")).contains("private");
    assertThat(CLOSED_EGRESS.privateRefusal("http://224.0.0.1/x")).contains("private");
    assertThat(ALLOW_LOOPBACK.privateRefusal("http://127.0.0.1/x")).isNull();
  }
}
