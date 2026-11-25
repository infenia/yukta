// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiRole;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
    return toServer(YuktaServer.baseUrl(), YuktaServer.key(role));
  }

  /**
   * A client for any server.
   *
   * @param baseUrl the server's base URL
   * @param key the key to send in {@code X-API-Key}, or null to send none
   * @return the client
   */
  public static Api toServer(final String baseUrl, final String key) {
    final WebTestClient.Builder builder =
        WebTestClient.bindToServer().baseUrl(baseUrl).responseTimeout(responseTimeout());
    return new Api(key == null ? builder.build() : builder.defaultHeader("X-API-Key", key).build());
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
    return reply(body == null ? request.exchange() : request.bodyValue(body).exchange());
  }

  /**
   * How long a call waits for the answer: the default 5 s, but longer on PostgreSQL, where a shared
   * CI runner answers the first calls (the OpenAPI document, a cold connection pool) slower.
   */
  private static Duration responseTimeout() {
    return PostgresDatabase.enabled() ? Duration.ofSeconds(30) : Duration.ofSeconds(5);
  }

  /**
   * A client that authenticates with a bearer token (an agent credential) instead of an API key.
   *
   * @param baseUrl the server's base URL
   * @param token the token
   * @return the client
   */
  public static Api bearer(final String baseUrl, final String token) {
    return new Api(
        WebTestClient.bindToServer()
            .baseUrl(baseUrl)
            .responseTimeout(responseTimeout())
            .defaultHeader("Authorization", "Bearer " + token)
            .build());
  }

  /**
   * Opens a server-sent event stream, checks it answers 200 as {@code text/event-stream}, reads for
   * a short time and closes it.
   *
   * @param request the GET request of the stream
   * @param window how long to read
   * @return the events received in that time, one per line (empty if none came)
   */
  public static String events(
      final WebTestClient.RequestHeadersSpec<?> request, final Duration window) {
    final var result =
        request
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(String.class);
    final List<String> received =
        result.getResponseBody().take(window).collectList().block(window.plusSeconds(10));
    return received == null ? "" : String.join("\n", received);
  }

  /**
   * Reads a server-sent event stream until the server ends it.
   *
   * @param request the GET request of the stream
   * @param limit how long to wait for the end; a stream that never ends fails the test
   * @return the events, one per line
   */
  public static String eventsUntilEnd(
      final WebTestClient.RequestHeadersSpec<?> request, final Duration limit) {
    final List<String> received =
        request
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block(limit);
    return received == null ? "" : String.join("\n", received);
  }

  /**
   * Reads an answer as text, whatever its status.
   *
   * @param response the response to a request built on {@link #client()}
   * @return its status and body
   */
  public static Reply reply(final WebTestClient.ResponseSpec response) {
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
    final Reply reply = reply(response);
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
  public JsonNode failure(final WebTestClient.ResponseSpec response, final int status) {
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
