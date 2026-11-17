// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import com.infenia.yukta.e2e.support.Scenarios;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /hooks/{session}/{path}} starts the workflow whose webhook trigger has that path,
 * when the body is signed with its secret.
 */
@E2eTest
@NoArgsConstructor
class WebhookE2eTest {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  private static final String SECRET_ENV = "YUKTA_SECRET_E2E_HOOK";
  private static final Duration WAIT = Duration.ofSeconds(30);

  private final Api admin = Api.forRole(com.infenia.yukta.security.ApiRole.ADMIN);

  private static String hooked(final String session, final String path) {
    return Workflows.hooked(session, path);
  }

  @SneakyThrows
  private static String sign(final String secret, final String body) {
    final Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void unknownHookIs404(final Scenarios scenarios) {
    final String session = Scenarios.unique("hook");
    scenarios.applySession(hooked(session, "go"));

    assertThat(admin.call("POST", "/hooks/" + session + "/other", "{}").status()).isEqualTo(404);
    assertThat(admin.call("POST", "/hooks/e2e-nope/go", "{}").status()).isEqualTo(404);
  }

  @Test
  void bodyWithoutValidSignatureIs401(final Scenarios scenarios) {
    final String session = Scenarios.unique("hook");
    scenarios.applySession(hooked(session, "go"));

    final Reply unsigned = admin.call("POST", "/hooks/" + session + "/go", "{}");

    assertThat(unsigned.status()).as(unsigned.body()).isEqualTo(401);
  }

  @Test
  @InJvmOnly
  @SneakyThrows
  void signedCallStartsTheBoundWorkflow(final Scenarios scenarios) {
    // the secret is read from the environment of the server process: the test JVM's, in-JVM only
    final String secret = System.getenv(SECRET_ENV);
    Assumptions.assumeTrue(secret != null, SECRET_ENV + " is not set in this JVM");
    final String session = Scenarios.unique("hook");
    scenarios.applySession(hooked(session, "go"));
    final String body = "{\"event\":\"e2e\"}";

    final Reply accepted =
        Api.reply(
            admin
                .client()
                .post()
                .uri("/hooks/{s}/go", session)
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", sign(secret, body))
                .bodyValue(body)
                .exchange());

    assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
    final String execution =
        JSON.readTree(accepted.body()).path("data").path("executionId").asString();
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isEqualTo("SUCCESS");
  }
}
