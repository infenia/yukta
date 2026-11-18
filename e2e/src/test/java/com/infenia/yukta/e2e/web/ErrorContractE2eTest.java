// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.security.ApiRole;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every failure is the {@code GlobalExceptionHandler} envelope {@code
 * {timestamp,status,message,error,path}} with no stack trace and no class name in it.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ErrorContractE2eTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static void assertEnvelope(final Reply reply, final int status, final String path) {
    assertThat(reply.status()).as(reply.body()).isEqualTo(status);
    final JsonNode body = JSON.readTree(reply.body());
    assertThat(body.path("status").asInt()).isEqualTo(status);
    assertThat(body.path("message").asString()).as(reply.body()).isNotBlank();
    assertThat(body.path("path").asString()).isEqualTo(path);
    assertThat(body.has("timestamp")).as(reply.body()).isTrue();
    assertThat(reply.body())
        .doesNotContain("Exception", "at com.", "com.infenia", "org.springframework", "stackTrace");
  }

  private static Reply call(final String method, final String path, final String body) {
    return Api.forRole(ApiRole.ADMIN).call(method, path, body);
  }

  @Test
  void validationIs400() {
    assertEnvelope(call("POST", "/api/workflow/start", "{}"), 400, "/api/workflow/start");
  }

  @Test
  void anUnknownSessionIs404() {
    assertEnvelope(
        call("GET", "/api/sessions/e2e-nope/config", null), 404, "/api/sessions/e2e-nope/config");
  }

  @Test
  void anUnknownExecutionIs404() {
    assertEnvelope(call("GET", "/api/executions/e2e-nope", null), 404, "/api/executions/e2e-nope");
  }

  @Test
  void wrongMethodIs405() {
    assertEnvelope(call("PATCH", "/api/sessions", "{}"), 405, "/api/sessions");
  }

  @Test
  void anOversizedBodyIs413() {
    assertEnvelope(
        call("POST", "/api/sessions/validate", "{\"x\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}"),
        413,
        "/api/sessions/validate");
  }

  @Test
  void wrongContentTypeIs415() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    final Reply reply =
        Api.reply(
            api.client()
                .post()
                .uri("/api/sessions/validate")
                .header("Content-Type", "text/plain")
                .bodyValue("{}")
                .exchange());

    assertEnvelope(reply, 415, "/api/sessions/validate");
  }

  @Test
  void malformedJsonIs400() {
    assertEnvelope(
        call("POST", "/api/sessions/validate", "{not json"), 400, "/api/sessions/validate");
  }

  @Test
  void anUnknownRouteIs404() {
    assertEnvelope(call("GET", "/api/e2e-no-such-route", null), 404, "/api/e2e-no-such-route");
  }
}
