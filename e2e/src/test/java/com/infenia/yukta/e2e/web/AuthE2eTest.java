// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.DedicatedServer;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import com.infenia.yukta.e2e.support.YuktaServer;
import com.infenia.yukta.security.ApiRole;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keys and roles over real HTTP. The rules come from {@code ApiRole.allows} and {@code
 * ApiKeyFilter}; every row of the table names the one it checks. A request with a key that is
 * allowed must not be answered 401 or 403 (the endpoint may still answer 400 or 404 to a sample
 * body); one that is not allowed is answered 403 with the filter's {status, message} body.
 *
 * <p>Requests without a valid key count against the filter's limit of 20 failures a minute per
 * address, so this class sends only a few of them.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AuthE2eTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String READ_RULE = "GET, HEAD and OPTIONS are open to every role";
  private static final String OPERATOR_RULE =
      "OPERATOR also writes under /api/executions/, /api/workflow/ and /api/conversations/";
  private static final String ADMIN_RULE = "every other write needs ADMIN";
  private static final String ACTUATOR_RULE = "the actuator (but its health) needs an ADMIN key";
  private static final String DOCS_RULE = "the OpenAPI document and Swagger UI answer GET only";
  private static final String INBOX_RULE = "any role writes its own inbox, /api/notifications/";

  /** One request, the lowest role that may send it (null: none) and the rule that says so. */
  private record Row(String rule, String method, String path, String body, ApiRole lowest) {}

  private static Stream<Arguments> table() {
    final Row[] rows = {
      new Row(READ_RULE, "GET", "/api/sessions", null, ApiRole.VIEWER),
      new Row(READ_RULE, "GET", "/api/plugins", null, ApiRole.VIEWER),
      new Row(READ_RULE, "GET", "/api/executions/e2e-nope", null, ApiRole.VIEWER),
      new Row(READ_RULE, "GET", "/api/supervisors", null, ApiRole.VIEWER),
      new Row(OPERATOR_RULE, "POST", "/api/executions/e2e-nope/stop", null, ApiRole.OPERATOR),
      new Row(
          OPERATOR_RULE,
          "POST",
          "/api/workflow/start",
          "{\"sessionId\":\"e2e-nope\",\"workflowId\":\"w\"}",
          ApiRole.OPERATOR),
      new Row(ADMIN_RULE, "POST", "/api/sessions/validate", "{}", ApiRole.ADMIN),
      new Row(ADMIN_RULE, "DELETE", "/api/sessions/e2e-nope", null, ApiRole.ADMIN),
      new Row(ADMIN_RULE, "PUT", "/api/supervisors/e2e-nope/assignments", "[]", ApiRole.ADMIN),
      new Row(INBOX_RULE, "POST", "/api/notifications/read", "{}", ApiRole.VIEWER),
      new Row(ACTUATOR_RULE, "GET", "/actuator/info", null, ApiRole.ADMIN),
      new Row(DOCS_RULE, "GET", "/v3/api-docs", null, ApiRole.VIEWER),
      new Row(DOCS_RULE, "POST", "/v3/api-docs", "{}", null),
    };
    return Stream.of(rows)
        .flatMap(row -> Stream.of(ApiRole.values()).map(role -> Arguments.of(row, role)));
  }

  private static Reply send(
      final Api api, final String method, final String path, final String body) {
    return api.call(method, path, body);
  }

  @ParameterizedTest(name = "{1} {0}")
  @MethodSource("table")
  @InJvmOnly
  void roleMatrix(final Row row, final ApiRole role) {
    final Reply reply = send(Api.forRole(role), row.method(), row.path(), row.body());
    final boolean allowed = row.lowest() != null && role.compareTo(row.lowest()) >= 0;

    if (allowed) {
      assertThat(reply.status())
          .as("%s %s as %s (%s): %s", row.method(), row.path(), role, row.rule(), reply.body())
          .isNotIn(401, 403);
    } else {
      assertThat(reply.status())
          .as("%s %s as %s (%s): %s", row.method(), row.path(), role, row.rule(), reply.body())
          .isEqualTo(403);
      assertThat(JSON.readTree(reply.body()).path("status").asInt()).isEqualTo(403);
      assertThat(JSON.readTree(reply.body()).path("message").asString()).isNotBlank();
    }
  }

  @Test
  void requestWithoutKeyIs401() {
    final Reply reply =
        send(Api.toServer(YuktaServer.baseUrl(), null), "GET", "/api/sessions", null);

    assertThat(reply.status()).isEqualTo(401);
    assertThat(JSON.readTree(reply.body()).path("status").asInt()).isEqualTo(401);
  }

  @Test
  void wrongKeyIs401() {
    final Reply reply =
        send(Api.toServer(YuktaServer.baseUrl(), "e2e-not-a-key"), "GET", "/api/sessions", null);

    assertThat(reply.status()).isEqualTo(401);
  }

  @Test
  void healthNeedsNoKey() {
    final Reply reply =
        send(Api.toServer(YuktaServer.baseUrl(), null), "GET", "/actuator/health", null);

    assertThat(reply.status()).isEqualTo(200);
  }

  @Test
  void bearerAndApiKeyHeadersAreBothAccepted() {
    final String key = YuktaServer.key(ApiRole.ADMIN);
    final Api bare = Api.toServer(YuktaServer.baseUrl(), null);

    final Reply bearer =
        Api.reply(
            bare.client()
                .get()
                .uri("/api/sessions")
                .header("Authorization", "Bearer " + key)
                .exchange());
    final Reply header =
        send(Api.toServer(YuktaServer.baseUrl(), key), "GET", "/api/sessions", null);

    assertThat(bearer.status()).as(bearer.body()).isEqualTo(200);
    assertThat(header.status()).as(header.body()).isEqualTo(200);
  }

  @Test
  @InJvmOnly
  void sessionScopedKeyIsRefusedOutsideItsSession() {
    final Api scoped = Api.toServer(YuktaServer.baseUrl(), YuktaServer.scopedKey());
    final String own = "/api/sessions/" + DedicatedServer.SCOPED_SESSION + "/config";

    assertThat(send(scoped, "GET", own, null).status()).as("its own session").isNotIn(401, 403);
    assertThat(send(scoped, "GET", "/api/sessions/e2e-scope-b/config", null).status())
        .as("another session")
        .isEqualTo(403);
    assertThat(send(scoped, "POST", "/api/executions/e2e-nope/stop", null).status())
        .as("an execution it does not own")
        .isEqualTo(403);
  }
}
