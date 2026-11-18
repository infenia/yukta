// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.DedicatedServer;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The host and origin check protects a server that has no API key (the loopback default), so it
 * runs on a server of its own with no keys. {@code HostOriginFilter}: a request must name a known
 * host (stops DNS rebinding), and a request that changes something must come from the same site.
 */
@E2eTest
@InJvmOnly
@NoArgsConstructor
class HostOriginE2eTest {

  private static DedicatedServer server;
  private static Api api;

  @BeforeAll
  static void start() {
    server = DedicatedServer.start(false);
    api = Api.toServer(server.baseUrl(), null);
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  private static Reply get(final String host, final String origin) {
    final var request = api.client().get().uri("/actuator/health").header("Host", host);
    return Api.reply((origin == null ? request : request.header("Origin", origin)).exchange());
  }

  private static Reply post(final String origin) {
    return Api.reply(
        api.client()
            .post()
            .uri("/api/sessions/validate")
            .header("Content-Type", "application/json")
            .header("Origin", origin)
            .bodyValue("{}")
            .exchange());
  }

  @Test
  void loopbackHostsAreAllowed() {
    assertThat(get("localhost", null).status()).isEqualTo(200);
    assertThat(get("127.0.0.1:1234", null).status()).isEqualTo(200);
    assertThat(get("[::1]", null).status()).isEqualTo(200);
  }

  @Test
  void foreignHostIsRefused() {
    assertThat(get("evil.example", null).status()).isEqualTo(403);
    assertThat(get("evil.example:9858", null).status()).isEqualTo(403);
  }

  @Test
  void foreignOriginIsRefusedForWritesNotReads() {
    assertThat(post("http://evil.example").status()).isEqualTo(403);
    assertThat(get("localhost", "http://evil.example").status()).isEqualTo(200);
  }

  @Test
  void theSameSiteOriginMayWrite() {
    final String site = server.baseUrl().replace("http://", "");

    final Reply reply =
        Api.reply(
            api.client()
                .post()
                .uri("/api/sessions/validate")
                .header("Content-Type", "application/json")
                .header("Origin", "http://" + site)
                .bodyValue("{}")
                .exchange());

    assertThat(reply.status()).as(reply.body()).isNotEqualTo(403);
  }
}
