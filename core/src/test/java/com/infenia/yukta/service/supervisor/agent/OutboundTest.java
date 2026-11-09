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

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;

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
          if (status.get() == 302) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
          }
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    logs.start();
    ((Logger) LoggerFactory.getLogger(Outbound.class)).addAppender(logs);
  }

  @AfterEach
  void shutDown() {
    server.stop(0);
    ((Logger) LoggerFactory.getLogger(Outbound.class)).detachAppender(logs);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  private static Egress allowLoopback() {
    return new Egress(EgressPolicy.OPEN, HostAllowList.from(List.of("127.0.0.1")));
  }

  private static Outbound outbound(final Egress egress) {
    return new Outbound(
        egress, WebClient.create(), JSON, name -> "TOKEN".equals(name) ? SECRET : null);
  }

  private Outbound.Config config() {
    return outbound(allowLoopback())
        .read(Map.of("url", url(), "secretHeaders", Map.of("X-Secret", "${env.TOKEN}")));
  }

  private static AgentHub.Sequenced tick(final long cursor) {
    return new AgentHub.Sequenced(cursor, new Signal.Tick(Instant.EPOCH));
  }

  private void deliver(final Outbound outbound, final AgentHub.Sequenced... signals) {
    outbound.deliver("ext", config(), Flux.just(signals)).block(Duration.ofSeconds(15));
  }

  @Test
  void eachSignalIsPostedAsTheAgentApiShowsItWithTheHeaders() throws Exception {
    deliver(outbound(allowLoopback()), tick(1), tick(2));

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
    final Outbound.Config config = config();

    deliver(outbound(allowLoopback()), tick(1), tick(2));

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
    status.set(302);

    deliver(outbound(allowLoopback()), tick(1));

    assertThat(paths).containsExactly("/hook");
  }

  @Test
  void loopbackTargetIsRefusedUnlessTheListNamesIt() {
    final Outbound closed = outbound(new Egress(EgressPolicy.OPEN, HostAllowList.from(null)));

    closed.deliver("ext", config(), Flux.just(tick(1))).block(Duration.ofSeconds(15));

    assertThat(bodies).isEmpty();
    assertThat(logs.list).hasSize(1);
  }

  @Test
  void settingsAreReadAndRefusedWithoutRepeatingValues() {
    final Outbound reader = outbound(allowLoopback());

    assertThat(reader.read(Map.of())).isNull();
    assertThat(reader.read(Map.of("url", " "))).isNull();
    final Outbound.Config read =
        reader.read(
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
    assertThat(reader.read(Map.of("url", "https://x.example")).timeout())
        .isEqualTo(Duration.ofSeconds(3));

    assertThatThrownBy(() -> reader.read(Map.of("url", "ftp://x.example")))
        .hasMessageContaining("http or https");
    assertThatThrownBy(() -> reader.read(Map.of("url", "https:///x")))
        .hasMessageContaining("with a host");
    assertThatThrownBy(() -> reader.read(Map.of("url", "https://x.example", "headers", "A: b")))
        .hasMessageContaining("headers must be an object");
    assertThatThrownBy(
            () ->
                reader.read(
                    Map.of(
                        "url",
                        "https://x.example",
                        "secretHeaders",
                        Map.of("X", "${env.MISSING}"))))
        .hasMessageContaining("MISSING")
        .hasMessageContaining("secretHeaders.X");
    assertThatThrownBy(() -> reader.read(Map.of("url", "https://x.example", "timeoutMs", 0)))
        .hasMessageContaining("timeoutMs must be a whole number from 1 to 10000");
    assertThatThrownBy(() -> reader.read(Map.of("url", "https://x.example", "timeoutMs", 1.5)))
        .hasMessageContaining("timeoutMs");
    assertThatThrownBy(
            () -> reader.read(Map.of("url", "https://x.example", "maxConcurrent", "many")))
        .hasMessageContaining("maxConcurrent");
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
    final Egress closed = new Egress(EgressPolicy.OPEN, HostAllowList.from(null));

    assertThat(closed.privateRefusal("http:///x")).contains("no host");
    assertThat(closed.privateRefusal("http://8.8.8.8/x")).isNull();
    assertThat(closed.privateRefusal("http://10.0.0.5/x")).contains("private");
    assertThat(closed.privateRefusal("http://224.0.0.1/x")).contains("private");
    assertThat(allowLoopback().privateRefusal("http://127.0.0.1/x")).isNull();
  }
}
