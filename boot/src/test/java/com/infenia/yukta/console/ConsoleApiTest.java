// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConsoleApiTest {

  @Test
  void fetchesWithTheKeyAndParsesTheBody() throws Exception {
    final AtomicReference<String> auth = new AtomicReference<>();
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/api",
        exchange -> {
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          final byte[] body = "{\"health\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
      assertThat(ConsoleApi.get(url, "k1").get("health").asText()).isEqualTo("UP");
      assertThat(auth.get()).isEqualTo("Bearer k1");
      assertThat(ConsoleApi.get(url, "").get("health").asText()).isEqualTo("UP");
      assertThat(auth.get()).isNull();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void timeOfDayIsTakenFromTheIsoStamp() {
    assertThat(ConsoleApi.time("2026-01-01T10:00:07.5Z")).isEqualTo("10:00:07");
  }

  @Test
  void stampWithoutTimeIsReturnedAsIs() {
    assertThat(ConsoleApi.time("2026-01-01")).isEqualTo("2026-01-01");
  }

  @Test
  void stampEndingAfterTheTimeKeepsWhatIsThere() {
    assertThat(ConsoleApi.time("2026-01-01T10:00")).isEqualTo("10:00");
  }

  @Test
  void unreachableServerFailsTheCall() {
    assertThatThrownBy(() -> ConsoleApi.get("http://127.0.0.1:1/api", ""))
        .isInstanceOf(IOException.class);
  }

  @Test
  void blankKeyIsNotSentAsBearer() {
    assertThatThrownBy(() -> ConsoleApi.get("http://127.0.0.1:1/api", " "))
        .isInstanceOf(IOException.class);
  }
}
