// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for NotifyTerminal against a local JDK HTTP server. */
class NotifyTerminalTest {

  /** Bodies received by the stub webhook. */
  private final List<String> bodies = new CopyOnWriteArrayList<>();

  private final NotifyTerminal terminal = new NotifyTerminal(WebClient.builder());
  private HttpServer server;
  private String url;
  private int status = 200;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static Flux<Message<?>> in(final Object payload) {
    return Flux.just(DefaultMessage.create(UUID.randomUUID(), payload));
  }

  @Test
  void consume_sendsPayloadTextFixedTextOrWholePayload() {
    StepVerifier.create(terminal.consume(in(Map.of("text", "hi")), Map.of("url", url)))
        .verifyComplete();
    StepVerifier.create(terminal.consume(in("raw"), Map.of("url", url))).verifyComplete();
    StepVerifier.create(terminal.consume(in("x"), Map.of("url", url, "text", "fixed")))
        .verifyComplete();
    assertThat(bodies)
        .containsExactly("{\"text\":\"hi\"}", "{\"text\":\"raw\"}", "{\"text\":\"fixed\"}");
  }

  @Test
  void consume_nonSuccessFailsTheNode() {
    status = 500;
    StepVerifier.create(terminal.consume(in("x"), Map.of("url", url))).expectError().verify();
  }

  @Test
  void metadataAndValidation() {
    assertThat(terminal.getType()).isEqualTo("NOTIFY_TERMINAL");
    assertThat(terminal.describe().usagePattern()).contains("url");
    StepVerifier.create(terminal.validateConfig(Map.of("url", url))).verifyComplete();
    StepVerifier.create(terminal.validateConfig(Map.of("url", "ftp://x")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void timeoutIsConfigurable() {
    StepVerifier.create(terminal.consume(in("x"), Map.of("url", url, "timeoutMs", 5000)))
        .verifyComplete();
  }
}
