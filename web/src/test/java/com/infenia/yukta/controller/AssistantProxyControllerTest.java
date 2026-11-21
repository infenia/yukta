// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Tests for {@link AssistantProxyController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestShouldIncludeAssert"
})
@ExtendWith(OutputCaptureExtension.class)
class AssistantProxyControllerTest {

  private static final String KEY = "sk-secret-123";
  private static final String HOSTS = "api.openai.com,localhost";
  private static final String ANTHROPIC = "https://anthropic.test/v1/messages";

  /** An upstream that records the request it got and answers with an event stream. */
  private static final class Upstream implements ExchangeFunction {
    private final AtomicReference<ClientRequest> request = new AtomicReference<>();
    private final HttpStatus status;

    /** Answers every call with this status. */
    private Upstream(final HttpStatus status) {
      this.status = status;
    }

    @Override
    public Mono<ClientResponse> exchange(final ClientRequest req) {
      request.set(req);
      return Mono.just(
          ClientResponse.create(status)
              .header("content-type", "text/event-stream")
              .header("set-cookie", "leak=1")
              .body("data: hi\n\n")
              .build());
    }
  }

  private static WebTestClient client(
      final Upstream upstream, final boolean enabled, final String hosts) {
    final var controller =
        new AssistantProxyController(
            WebClient.builder().exchangeFunction(upstream),
            enabled,
            List.of(hosts.split(",")),
            ANTHROPIC);
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  private static WebTestClient.RequestBodySpec post(
      final WebTestClient client, final String provider) {
    return client.post().uri("/api/assistant/proxy/" + provider);
  }

  @Test
  void anthropic_relaysBodyWithKeyAndStreamsTheReplyBack() {
    final var upstream = new Upstream(HttpStatus.OK);

    final var body =
        post(client(upstream, true, HOSTS), "anthropic")
            .header(AssistantProxyController.KEY_HEADER, " " + KEY + " ")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"model\":\"m\"}")
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .expectHeader()
            .doesNotExist("set-cookie")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).isEqualTo("data: hi\n\n");
    final ClientRequest sent = upstream.request.get();
    assertThat(sent.url().toString()).isEqualTo(ANTHROPIC);
    assertThat(sent.headers().getFirst("x-api-key")).isEqualTo(KEY);
    assertThat(sent.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
    assertThat(sent.headers().getFirst("Authorization")).isNull();
  }

  @Test
  void openai_usesTheDefaultBaseAndBearerKey() {
    final var upstream = new Upstream(HttpStatus.OK);

    post(client(upstream, true, HOSTS), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();

    final ClientRequest sent = upstream.request.get();
    assertThat(sent.url().toString()).isEqualTo("https://api.openai.com/v1/chat/completions");
    assertThat(sent.headers().getFirst("Authorization")).isEqualTo("Bearer " + KEY);
    assertThat(sent.headers().getFirst("x-api-key")).isNull();
  }

  @Test
  void openai_blankBaseUrlUsesTheDefault() {
    final var upstream = new Upstream(HttpStatus.OK);

    post(client(upstream, true, HOSTS), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .header(AssistantProxyController.BASE_URL_HEADER, "  ")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(upstream.request.get().url().toString())
        .isEqualTo("https://api.openai.com/v1/chat/completions");
  }

  @Test
  void openai_usesListedCustomBaseWithoutTrailingSlash() {
    final var upstream = new Upstream(HttpStatus.OK);

    post(client(upstream, true, HOSTS), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .header(AssistantProxyController.BASE_URL_HEADER, "http://localhost:11434/v1/")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(upstream.request.get().url().toString())
        .isEqualTo("http://localhost:11434/v1/chat/completions");
  }

  @Test
  void upstreamErrorStatusPassesThrough() {
    post(client(new Upstream(HttpStatus.UNAUTHORIZED), true, HOSTS), "anthropic")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void emptyBodyIsRelayedAsEmpty() {
    final var upstream = new Upstream(HttpStatus.OK);

    post(client(upstream, true, HOSTS), "anthropic")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(upstream.request.get()).isNotNull();
  }

  @Test
  void baseUrlOutsideTheAllowListIsForbiddenAndNothingIsCalled() {
    final var upstream = new Upstream(HttpStatus.OK);

    post(client(upstream, true, HOSTS), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .header(AssistantProxyController.BASE_URL_HEADER, "https://evil.example/v1")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden();

    assertThat(upstream.request.get()).isNull();
  }

  @Test
  void emptyAllowListAllowsNoBaseUrl() {
    post(client(new Upstream(HttpStatus.OK), true, ""), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void baseUrlThatIsNotHttpIsBadRequest() {
    post(client(new Upstream(HttpStatus.OK), true, HOSTS), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .header(AssistantProxyController.BASE_URL_HEADER, "file:///etc/passwd")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void missingOrBlankKeyIsBadRequest() {
    final var client = client(new Upstream(HttpStatus.OK), true, HOSTS);

    post(client, "anthropic").bodyValue("{}").exchange().expectStatus().isBadRequest();
    post(client, "anthropic")
        .header(AssistantProxyController.KEY_HEADER, "  ")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void unknownProviderIsNotFound() {
    post(client(new Upstream(HttpStatus.OK), true, HOSTS), "gemini")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void disabledRelayIsNotFound() {
    final var upstream = new Upstream(HttpStatus.OK);

    post(client(upstream, false, HOSTS), "anthropic")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(upstream.request.get()).isNull();
  }

  @Test
  void bodyOverTheLimitIsRejected() {
    final var upstream = new Upstream(HttpStatus.OK);
    final byte[] big = new byte[AssistantProxyController.MAX_BODY_BYTES + 1];

    post(client(upstream, true, HOSTS), "anthropic")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue(new String(big, StandardCharsets.ISO_8859_1))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);

    assertThat(upstream.request.get()).isNull();
  }

  @Test
  void theKeyIsNeverLogged(final CapturedOutput output) {
    post(client(new Upstream(HttpStatus.UNAUTHORIZED), true, HOSTS), "anthropic")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    post(client(new Upstream(HttpStatus.UNAUTHORIZED), true, HOSTS), "openai")
        .header(AssistantProxyController.KEY_HEADER, KEY)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    assertThat(output.getAll()).doesNotContain(KEY);
  }
}
