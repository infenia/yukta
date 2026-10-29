// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A loopback HTTP server with one canned reply that remembers what it was sent. */
@SuppressWarnings({
  "PMD.CommentDefaultAccessModifier",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.AvoidUsingVolatile",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass"
})
final class FakeHttp implements AutoCloseable {

  /** One request the server received. */
  record Request(String method, String path, String body) {}

  private final HttpServer server;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private volatile String reply = "{}";

  FakeHttp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          final String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
          final byte[] out = reply.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  FakeHttp reply(final String json) {
    this.reply = json;
    return this;
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<Request> requests() {
    return requests;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
