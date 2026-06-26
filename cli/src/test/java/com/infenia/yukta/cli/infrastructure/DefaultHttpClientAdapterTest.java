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
package com.infenia.yukta.cli.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultHttpClientAdapterTest {

  private DefaultHttpClientAdapter adapter;
  private HttpServer mockServer;

  @BeforeEach
  void setUp() {
    adapter = new DefaultHttpClientAdapter();
  }

  @AfterEach
  void tearDown() {
    if (mockServer != null) {
      mockServer.stop(0);
    }
  }

  @Test
  void healthCheck_status200_returnsTrue() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    mockServer.start();

    boolean result = adapter.healthCheck(port);

    assertThat(result).isTrue();
  }

  @Test
  void healthCheck_status503_returnsFalse() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(503, 0);
          exchange.close();
        });
    mockServer.start();

    boolean result = adapter.healthCheck(port);

    assertThat(result).isFalse();
  }

  @Test
  void healthCheck_status500_returnsFalse() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(500, 0);
          exchange.close();
        });
    mockServer.start();

    boolean result = adapter.healthCheck(port);

    assertThat(result).isFalse();
  }

  @Test
  void healthCheck_connectionRefused_throwsConnectException() {
    int unavailablePort = 1;

    assertThatThrownBy(() -> adapter.healthCheck(unavailablePort))
        .isInstanceOf(ConnectException.class)
        .hasMessageContaining("Daemon not responding");
  }

  @Test
  void healthCheck_invalidPort_throwsException() {
    assertThatThrownBy(() -> adapter.healthCheck(0)).isInstanceOf(Exception.class);
  }

  @Test
  void healthCheck_adapterIsInstantiable() {
    DefaultHttpClientAdapter testAdapter = new DefaultHttpClientAdapter();
    assertThat(testAdapter).isNotNull();
  }

  @Test
  void healthCheck_status404_returnsFalse() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(404, 0);
          exchange.close();
        });
    mockServer.start();

    boolean result = adapter.healthCheck(port);

    assertThat(result).isFalse();
  }

  @Test
  void healthCheck_multipleHealthChecks_allSucceed() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    mockServer.start();

    for (int i = 0; i < 3; i++) {
      boolean result = adapter.healthCheck(port);
      assertThat(result).isTrue();
    }
  }

  @Test
  void healthCheck_correctPort_usesProvidedPort() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    mockServer.start();

    boolean result = adapter.healthCheck(port);

    assertThat(result).isTrue();
  }

  @Test
  void healthCheck_status201_returnsFalse() throws Exception {
    mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = mockServer.getAddress().getPort();

    mockServer.createContext(
        "/actuator/health",
        exchange -> {
          exchange.sendResponseHeaders(201, 0);
          exchange.close();
        });
    mockServer.start();

    boolean result = adapter.healthCheck(port);

    assertThat(result).isFalse();
  }
}
