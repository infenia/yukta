// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** The webhook responder: a named target, the egress rules, a signed redacted body. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WebhookInputResponderTest {

  private static final Instant AT = Instant.parse("2026-11-03T10:00:00Z");

  private final List<ClientRequest> sent = new CopyOnWriteArrayList<>();
  private HttpStatus answer = HttpStatus.OK;

  private static InputRequest request() {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k",
            InputKind.APPROVAL,
            "Deploy?",
            Map.of("branch", "main"),
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofHours(1),
            TimeoutAction.FAIL,
            null,
            null,
            "TEST");
    return new InputRequest(
        "r1",
        "k",
        "s",
        "w",
        "e",
        "n",
        spec,
        InputState.OPEN,
        0,
        "hook",
        List.of(),
        "advice",
        AT.plusSeconds(3600),
        AT,
        AT);
  }

  private WebhookInputResponder responder(final MockEnvironment env, final EgressPolicy egress) {
    final WebClient.Builder builder =
        WebClient.builder()
            .exchangeFunction(
                call -> {
                  sent.add(call);
                  return Mono.just(ClientResponse.create(answer).build());
                });
    return new WebhookInputResponder(builder, egress, env, JsonMapper.builder().build());
  }

  private static Map<String, Object> settings(final String name) {
    return Map.of("name", name, "answerToken", "tok", "answerExpires", 4_000_000_000L);
  }

  private static MockEnvironment target() {
    return new MockEnvironment()
        .withProperty("yukta.input.responders.ops.url", "https://hooks.example.com/yukta")
        .withProperty(
            "yukta.input.responders.ops.answer-url",
            "https://yukta.example.com/api/inputs/token-answer")
        .withProperty("yukta.input.responders.ops.headers.X-Team", "ops")
        .withProperty("yukta.input.responders.ops.headers.Authorization", "Bearer ${hook.secret}")
        .withProperty("hook.secret", "s3cr3t");
  }

  @Test
  void postsTheRedactedRequestWithTheTokenAndHeaders() {
    final WebhookInputResponder webhook = responder(target(), EgressPolicy.OPEN);

    StepVerifier.create(webhook.deliver(request(), settings("ops"))).verifyComplete();

    final ClientRequest call = sent.getFirst();
    assertThat(call.url()).isEqualTo(URI.create("https://hooks.example.com/yukta"));
    assertThat(call.headers().getFirst("X-Team")).isEqualTo("ops");
    assertThat(call.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer s3cr3t");
    assertThat(call.headers().getFirst(WebhookInputResponder.SIGNATURE_HEADER)).isNull();
    assertThat(webhook.type()).isEqualTo("webhook");
  }

  @Test
  void signsTheBodyWhenAnSigningSecretIsSet() {
    final MockEnvironment env =
        new MockEnvironment()
            .withProperty("yukta.input.responders.ops.url", "https://hooks.example.com/yukta")
            .withProperty("yukta.input.responders.ops.signing-secret", "shh")
            .withProperty("yukta.input.responders.ops.timeout", "2s");

    StepVerifier.create(responder(env, EgressPolicy.OPEN).deliver(request(), settings("ops")))
        .verifyComplete();

    assertThat(sent.getFirst().headers().getFirst(WebhookInputResponder.SIGNATURE_HEADER))
        .startsWith("sha256=")
        .hasSize("sha256=".length() + 64);
  }

  @Test
  void anUnknownTargetIsAnError() {
    final WebhookInputResponder webhook = responder(new MockEnvironment(), EgressPolicy.OPEN);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> webhook.deliver(request(), settings("nope")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void hostTheEgressPolicyRefusesIsNotCalled() {
    final EgressPolicy strict =
        new EgressPolicy(HostAllowList.from(List.of("only.example.com")), Map.of(), Map.of());

    StepVerifier.create(responder(target(), strict).deliver(request(), settings("ops")))
        .expectError(IllegalArgumentException.class)
        .verify();

    assertThat(sent).isEmpty();
  }

  @Test
  void failingReceiverIsAnErrorSoTheWorkerTriesAgain() {
    answer = HttpStatus.INTERNAL_SERVER_ERROR;

    StepVerifier.create(responder(target(), EgressPolicy.OPEN).deliver(request(), settings("ops")))
        .expectError()
        .verify();
  }

  @Test
  void theBodyHoldsTheRequestAndTheTokenAndNeverAnAnswer() {
    final var target =
        new WebhookInputResponder.Target(
            "https://hooks.example.com/yukta", null, null, null, "https://y.example.com/answer");

    final Map<String, Object> body =
        WebhookInputResponder.payload(request(), settings("ops"), target);

    assertThat(body)
        .containsEntry("requestId", "r1")
        .containsEntry("tier", "hook")
        .containsEntry("prompt", "Deploy?")
        .containsEntry("context", Map.of("branch", "main"))
        .containsEntry("token", "tok")
        .containsEntry("tokenExpires", 4_000_000_000L)
        .containsEntry("answerUrl", "https://y.example.com/answer")
        .doesNotContainKeys("answer", "decision", "data", "response");
  }
}
