// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiRole;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@link WebTestClient} bound to the server that sends one role's API key, and helpers for the
 * {@code {timestamp,status,message,path,data}} envelope. It never retries.
 */
@SuppressWarnings({"PMD.ShortClassName", "PMD.AvoidFieldNameMatchingMethodName"})
public final class Api {

  private static final ObjectMapper JSON = new ObjectMapper();

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

  /** A status and a body, for tests that assert on text. */
  public record Reply(int status, String body) {}

  /**
   * Sends one JSON request and returns the answer as text, whatever the status.
   *
   * @param method the HTTP method
   * @param path the path and query
   * @param body the JSON body, or null for none
   * @return the status and the body ({@code ""} when empty)
   */
  public Reply call(final String method, final String path, final String body) {
    final var request =
        client
            .method(HttpMethod.valueOf(method))
            .uri(path)
            .header("Content-Type", "application/json");
    return read(body == null ? request.exchange() : request.bodyValue(body).exchange());
  }

  private static Reply read(final WebTestClient.ResponseSpec response) {
    final var result = response.returnResult(String.class);
    final byte[] content = result.getResponseBodyContent();
    return new Reply(
        result.getStatus().value(),
        content == null ? "" : new String(content, StandardCharsets.UTF_8));
  }

  /**
   * Checks that the answer is a success and returns its {@code data}.
   *
   * @param response the response to a request
   * @return the {@code data} field of the envelope (a missing node if there is none)
   */
  public JsonNode data(final WebTestClient.ResponseSpec response) {
    final Reply reply = read(response);
    assertThat(reply.status() / 100).as("answer %s: %s", reply.status(), reply.body()).isEqualTo(2);
    final JsonNode body = JSON.readTree(reply.body().isEmpty() ? "{}" : reply.body());
    assertThat(body.has("status")).as("the envelope has a status: %s", reply.body()).isTrue();
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
