// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiRole;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

/**
 * A {@link WebTestClient} bound to the server that sends one role's API key, and helpers for the
 * {@code {timestamp,status,message,path,data}} envelope. It never retries.
 */
@SuppressWarnings({"PMD.ShortClassName", "PMD.AvoidFieldNameMatchingMethodName"})
public final class Api {

  private final WebTestClient client;

  private Api(final WebTestClient client) {
    this.client = client;
  }

  /**
   * A client that sends the key of a role.
   *
   * @param role the role
   * @return the client
   */
  public static Api forRole(final ApiRole role) {
    return new Api(
        WebTestClient.bindToServer()
            .baseUrl(YuktaServer.baseUrl())
            .defaultHeader("X-API-Key", YuktaServer.key(role))
            .build());
  }

  /**
   * The underlying client.
   *
   * @return the client
   */
  public WebTestClient client() {
    return client.mutate().build();
  }

  /**
   * Checks that the answer is a success and returns its {@code data}.
   *
   * @param response the response to a request
   * @return the {@code data} field of the envelope (a missing node if there is none)
   */
  public JsonNode data(final WebTestClient.ResponseSpec response) {
    final JsonNode body =
        response
            .expectStatus()
            .is2xxSuccessful()
            .expectBody(JsonNode.class)
            .returnResult()
            .getResponseBody();
    assertThat(body).as("response body").isNotNull();
    assertThat(body.has("status")).as("the envelope has a status").isTrue();
    return body.path("data");
  }

  /**
   * Checks that the answer is the error envelope for a status.
   *
   * @param response the response to a request
   * @param status the expected HTTP status
   * @return the envelope, to assert the message further
   */
  public JsonNode error(final WebTestClient.ResponseSpec response, final int status) {
    final JsonNode body =
        response
            .expectStatus()
            .isEqualTo(status)
            .expectBody(JsonNode.class)
            .returnResult()
            .getResponseBody();
    assertThat(body).as("error body").isNotNull();
    assertThat(body.path("status").asInt()).isEqualTo(status);
    assertThat(body.path("message").asString()).isNotBlank();
    assertThat(body.path("path").asString()).startsWith("/");
    return body;
  }
}
