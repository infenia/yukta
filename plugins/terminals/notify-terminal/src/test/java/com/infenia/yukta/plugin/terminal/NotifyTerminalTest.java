// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Tests for NotifyTerminal against a local JDK HTTP server. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class NotifyTerminalTest {

  /** Config key. */
  private static final String URL_KEY = "url";

  /** Bodies received by the stub webhook. */
  private final List<String> bodies = new CopyOnWriteArrayList<>();

  /** Terminal under test. */
  private final NotifyTerminal terminal = new NotifyTerminal(WebClient.builder());

  /** Stub webhook server. */
  private HttpServer server;

  /** Webhook URL of the stub. */
  private String url;

  /** HTTP status the stub answers with. */
  private int status = 200;

  @BeforeEach
  @SuppressWarnings("PMD.LawOfDemeter")
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    final String host = server.getAddress().getAddress().getHostAddress();
    url =
        "http://"
            + (host.contains(":") ? "[" + host + "]" : host)
            + ":"
            + server.getAddress().getPort()
            + "/hook";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static Flux<Message<?>> messages(final Object payload) {
    return Flux.just(DefaultMessage.create(UUID.randomUUID(), payload));
  }

  @Test
  void consume_sendsPayloadTextFixedTextOrWholePayload() {
    StepVerifier.create(terminal.consume(messages(Map.of("text", "hi")), Map.of(URL_KEY, url)))
        .verifyComplete();
    StepVerifier.create(terminal.consume(messages("raw"), Map.of(URL_KEY, url))).verifyComplete();
    StepVerifier.create(terminal.consume(messages("x"), Map.of(URL_KEY, url, "text", "fixed")))
        .verifyComplete();
    assertThat(bodies)
        .containsExactly("{\"text\":\"hi\"}", "{\"text\":\"raw\"}", "{\"text\":\"fixed\"}");
  }

  @Test
  void consume_hostOutsideTheWorkflowEgressListIsNotCalled() {
    terminal.setEgressPolicy(
        new EgressPolicy(
            HostAllowList.from(null),
            Map.of("wf", HostAllowList.from(List.of("hooks.example.com"))),
            Map.of()));

    StepVerifier.create(
            terminal
                .consume(messages("x"), Map.of(URL_KEY, url))
                .contextWrite(Context.of("workflowId", "wf")))
        .expectErrorSatisfies(
            e -> assertThat(e).hasMessageContaining("yukta.egress.by-workflow.wf"))
        .verify();
    assertThat(bodies).isEmpty();
  }

  @Test
  void consume_nonSuccessFailsTheNode() {
    status = 500;
    StepVerifier.create(terminal.consume(messages("x"), Map.of(URL_KEY, url)))
        .expectError()
        .verify();
  }

  @Test
  void consume_continueModeKeepsSending() {
    status = 500;
    StepVerifier.create(
            terminal.consume(
                Flux.concat(messages("x"), messages("y")),
                Map.of(URL_KEY, url, "failureMode", "CONTINUE")))
        .verifyComplete();
    assertThat(bodies).hasSize(2);
  }

  @Test
  void consume_redirectFailsTheNode() {
    status = 302;
    StepVerifier.create(terminal.consume(messages("x"), Map.of(URL_KEY, url)))
        .expectError()
        .verify();
  }

  @Test
  void consume_insecureUrlIsRejectedBeforeSending() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> terminal.consume(messages("x"), Map.of(URL_KEY, "http://hooks.example/x")));
  }

  @Test
  void metadataAndValidation() {
    assertThat(terminal.getType()).isEqualTo("NOTIFY_TERMINAL");
    assertThat(terminal.describe().usagePattern()).contains(URL_KEY);
    StepVerifier.create(terminal.validateConfig(Map.of(URL_KEY, url))).verifyComplete();
    StepVerifier.create(terminal.validateConfig(Map.of(URL_KEY, "https://hooks.example/x")))
        .verifyComplete();
    for (final String local :
        List.of("http://[::1]:9/x", "http://127.0.0.1:9/x", "http://LOCALHOST/x")) {
      StepVerifier.create(terminal.validateConfig(Map.of(URL_KEY, local))).verifyComplete();
    }
    StepVerifier.create(terminal.validateConfig(Map.of(URL_KEY, "mailto:a@b.c")))
        .expectError(IllegalArgumentException.class)
        .verify();
    for (final String bad : List.of("ftp://x", "http://hooks.example/x", "not a url")) {
      StepVerifier.create(terminal.validateConfig(Map.of(URL_KEY, bad)))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
  }

  @Test
  void timeoutIsConfigurable() {
    StepVerifier.create(terminal.consume(messages("x"), Map.of(URL_KEY, url, "timeoutMs", 5000)))
        .verifyComplete();
  }

  @Test
  void describe_terminalProvidesNodeDesign() {
    assertThat(terminal.describe().uiDesign())
        .hasValueSatisfying(
            design -> {
              assertThat(design.label()).isNotBlank();
              assertThat(design.width()).isEqualTo(140);
              assertThat(design.height()).isEqualTo(80);
            });
  }

  private static ObjectProvider<PluginMetricsReporter> provider(final PluginMetricsReporter value) {
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("reporter", value);
    return factory.getBeanProvider(PluginMetricsReporter.class);
  }

  @Test
  void springConstructorReportsMetrics() {
    final List<String> counts = new ArrayList<>();
    final NotifyTerminal reporting =
        new NotifyTerminal(
            WebClient.builder(), provider((node, metric, tag) -> counts.add(metric + ":" + tag)));
    StepVerifier.create(reporting.consume(messages("x"), Map.of(URL_KEY, url))).verifyComplete();
    assertThat(counts).containsExactly("notify:OK");
    final var empty = new StaticListableBeanFactory().getBeanProvider(PluginMetricsReporter.class);
    assertThat(new NotifyTerminal(WebClient.builder(), empty).getType())
        .isEqualTo("NOTIFY_TERMINAL");
  }
}
