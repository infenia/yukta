// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.notify.ChannelContext;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
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

/** The webhook channel: a named target, the egress rules, a signed redacted additive body. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods", "PMD.ExcessiveImports"})
class WebhookChannelTest {

  private static final Instant AT = Instant.parse("2026-11-03T10:00:00Z");
  private static final String URL = "https://hooks.example.com/yukta";

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

  private static Notification notification(final NotificationKind kind) {
    return new Notification(
        "n1",
        "input:r1",
        kind,
        NotificationSeverity.WARN,
        "input",
        "Deploy?",
        null,
        "s",
        "w",
        "e",
        "n",
        kind == NotificationKind.ACTION ? "r1" : null,
        List.of("role:OPERATOR"),
        Notification.State.ACTIVE,
        null,
        1,
        1,
        AT,
        AT);
  }

  private WebhookChannel channel(final MockEnvironment env, final EgressPolicy egress) {
    final WebClient.Builder builder =
        WebClient.builder()
            .exchangeFunction(call -> {
              sent.add(call);
              return Mono.just(ClientResponse.create(answer).build());
            });
    return new WebhookChannel(builder, egress, env, JsonMapper.builder().build());
  }

  private static ChannelContext context(
      final Map<String, Object> settings, final @Nullable InputRequest request) {
    return new ChannelContext(
        "ops",
        settings,
        request,
        request == null ? null : "tok",
        request == null ? null : Instant.ofEpochSecond(4_000_000_000L));
  }

  private static Map<String, Object> target() {
    return Map.of(
        "url",
        URL,
        "answer-url",
        "https://yukta.example.com/api/inputs/token-answer",
        "headers",
        Map.of("X-Team", "ops", "Authorization", "Bearer ${hook.secret}"));
  }

  private MockEnvironment env() {
    return new MockEnvironment().withProperty("hook.secret", "s3cr3t");
  }

  @Test
  void postsToTheTargetWithItsHeaders() {
    final WebhookChannel webhook = channel(env(), EgressPolicy.OPEN);

    StepVerifier.create(
            webhook.deliver(notification(NotificationKind.ACTION), context(target(), request())))
        .verifyComplete();

    final ClientRequest call = sent.getFirst();
    assertThat(call.url()).isEqualTo(URI.create(URL));
    assertThat(call.headers().getFirst("X-Team")).isEqualTo("ops");
    assertThat(call.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer s3cr3t");
    assertThat(call.headers().getFirst(WebhookChannel.SIGNATURE_HEADER)).isNull();
    assertThat(webhook.type()).isEqualTo("webhook");
  }

  @Test
  void signsTheBodyWhenSigningSecretIsSet() {
    final Map<String, Object> settings =
        Map.of("url", URL, "signing-secret", "shh", "timeout", "2s");

    StepVerifier.create(
            channel(env(), EgressPolicy.OPEN)
                .deliver(notification(NotificationKind.INFO), context(settings, null)))
        .verifyComplete();

    assertThat(sent.getFirst().headers().getFirst(WebhookChannel.SIGNATURE_HEADER))
        .startsWith("sha256=")
        .hasSize("sha256=".length() + 64);
  }

  @Test
  void unknownTargetIsAnError() {
    final WebhookChannel webhook = channel(env(), EgressPolicy.OPEN);

    assertThatThrownBy(
            () -> webhook.deliver(notification(NotificationKind.INFO), context(Map.of(), null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops");
  }

  @Test
  void hostTheEgressPolicyRefusesIsNotCalled() {
    final EgressPolicy strict =
        new EgressPolicy(HostAllowList.from(List.of("only.example.com")), Map.of(), Map.of());

    StepVerifier.create(
            channel(env(), strict)
                .deliver(notification(NotificationKind.INFO), context(target(), null)))
        .expectError(IllegalArgumentException.class)
        .verify();

    assertThat(sent).isEmpty();
  }

  @Test
  void failingReceiverIsAnErrorSoTheWorkerTriesAgain() {
    answer = HttpStatus.INTERNAL_SERVER_ERROR;

    StepVerifier.create(
            channel(env(), EgressPolicy.OPEN)
                .deliver(notification(NotificationKind.INFO), context(target(), null)))
        .expectError()
        .verify();
  }

  @Test
  void actionKeepsTheFieldsOfAlpha4AndGainsTheNotificationObject() {
    final var target =
        new WebhookChannel.Target(URL, null, null, null, "https://y.example.com/answer");

    final Map<String, Object> body =
        WebhookChannel.payload(
            notification(NotificationKind.ACTION), context(Map.of(), request()), target);

    assertThat(body)
        .containsEntry("requestId", "r1")
        .containsEntry("tier", "hook")
        .containsEntry("prompt", "Deploy?")
        .containsEntry("context", Map.of("branch", "main"))
        .containsEntry("token", "tok")
        .containsEntry("tokenExpires", 4_000_000_000L)
        .containsEntry("answerUrl", "https://y.example.com/answer")
        .containsKey("notification")
        .doesNotContainKeys("answer", "decision", "data", "response");
    @SuppressWarnings("unchecked")
    final Map<String, Object> summary = (Map<String, Object>) body.get("notification");
    assertThat(summary)
        .containsEntry("id", "n1")
        .containsEntry("category", "input")
        .doesNotContainKey("audience");
  }

  @Test
  void rulesActionHasNoTokenAndAnInfoCarriesOnlyTheNotification() {
    final var target = new WebhookChannel.Target(URL, null, null, null, null);
    final ChannelContext noToken = new ChannelContext("ops", Map.of(), request(), null, null);

    final Map<String, Object> action =
        WebhookChannel.payload(notification(NotificationKind.ACTION), noToken, target);
    final Map<String, Object> info =
        WebhookChannel.payload(
            notification(NotificationKind.INFO), context(Map.of(), null), target);

    assertThat(action).containsEntry("token", null).containsEntry("tokenExpires", null);
    assertThat(info).containsOnlyKeys("notification");
  }
}
