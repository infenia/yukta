// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.filter.ApiKeyFilter;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.service.input.token.InvalidAnswerTokenException;
import com.infenia.yukta.service.input.token.TokenAnswerService;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** The answer with a one-time token: open to anyone with a token, and to no other input path. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.UnitTestShouldIncludeAssert"
})
class TokenAnswerControllerTest {

  private static final String BODY =
      "{\"requestId\":\"%s\",\"tier\":\"hook\",\"expires\":4102444800,\"token\":\"abc\","
          + "\"decision\":\"approve\"}";

  private final TokenAnswerService service = mock(TokenAnswerService.class);
  private WebTestClient open;
  private WebTestClient keyed;

  @BeforeEach
  void setUp() {
    final TokenAnswerController controller = new TokenAnswerController(service);
    when(service.answer(any())).thenReturn(Mono.empty());
    open =
        WebTestClient.bindToController(controller)
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    final MockEnvironment keys =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "ops")
            .withProperty("yukta.api-keys[0].key", "o-key")
            .withProperty("yukta.api-keys[0].role", "OPERATOR")
            .withProperty("yukta.api-keys[0].sessions", "*");
    keyed =
        WebTestClient.bindToController(controller)
            .webFilter(new ApiKeyFilter(ApiKeyAuthenticator.read(keys), null, null))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private WebTestClient.ResponseSpec post(
      final WebTestClient client, final String path, final String body) {
    return client
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  @Test
  void goodTokenAnswersWithoutAnApiKey() {
    post(keyed, "/api/inputs/token-answer", BODY.formatted("r1")).expectStatus().isOk();

    verify(service).answer(any());
  }

  @Test
  void everyBadTokenIsTheSameForbiddenAnswer() {
    when(service.answer(any())).thenReturn(Mono.error(new InvalidAnswerTokenException()));

    for (final String id : new String[] {"unknown", "used", "expired"}) {
      post(open, "/api/inputs/token-answer", BODY.formatted(id))
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.message")
          .isEqualTo("Invalid or expired token");
    }
  }

  @Test
  void bodyThatIsNotAnAnswerIsRefused() {
    post(open, "/api/inputs/token-answer", "{\"requestId\":\"r1\"}").expectStatus().isBadRequest();
  }

  @Test
  void oneRequestCannotBeHammered() {
    for (int i = 0; i < 10; i++) {
      post(open, "/api/inputs/token-answer", BODY.formatted("same")).expectStatus().isOk();
    }

    post(open, "/api/inputs/token-answer", BODY.formatted("same"))
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals("Retry-After", "60");
  }

  @Test
  void theAddressOfTheCallerIsWhatIsLimited() {
    final TokenAnswerController controller = new TokenAnswerController(service);
    final org.springframework.mock.http.server.reactive.MockServerHttpRequest call =
        org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(
                "/api/inputs/token-answer")
            .remoteAddress(new java.net.InetSocketAddress("10.1.2.3", 4000))
            .build();
    final var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(call);
    final com.infenia.yukta.dto.request.TokenAnswerRequest body =
        new com.infenia.yukta.dto.request.TokenAnswerRequest(
            "r1", "hook", 4_102_444_800L, "abc", "approve", null, null, null);

    for (int i = 0; i < 10; i++) {
      controller.answer(body, exchange).block();
    }

    org.assertj.core.api.Assertions.assertThat(
            java.util.Objects.requireNonNull(controller.answer(body, exchange).block())
                .getStatusCode()
                .value())
        .isEqualTo(429);
  }

  @Test
  void oneAddressCannotTryManyRequests() {
    for (int i = 0; i < 30; i++) {
      post(open, "/api/inputs/token-answer", BODY.formatted("r" + i)).expectStatus().isOk();
    }

    post(open, "/api/inputs/token-answer", BODY.formatted("another")).expectStatus().isEqualTo(429);
  }

  @Test
  void noOtherInputPathIsOpenWithoutKey() {
    post(keyed, "/api/inputs/other", "{}").expectStatus().isUnauthorized();
    post(keyed, "/api/inputs/token-answer/extra", "{}").expectStatus().isUnauthorized();
    post(keyed, "/api/executions/e1/inputs/r1/answer", "{\"decision\":\"x\"}")
        .expectStatus()
        .isUnauthorized();
    post(keyed, "/api/sessions/s1/inputs", "{}").expectStatus().isUnauthorized();
    keyed.get().uri("/api/inputs/token-answer").exchange().expectStatus().isUnauthorized();
    keyed.put().uri("/api/inputs/token-answer").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void trickyPathDoesNotReachTheOpenEndpoint() {
    for (final String path :
        new String[] {
          "/api/inputs/token-answer%2f",
          "/api/inputs/token-answer%2F",
          "/api/inputs%2ftoken-answer",
          "/api/inputs/token-answer;x=1",
          "/api/inputs/token-answer%00",
          "/api/inputs/%2e%2e/inputs/token-answer"
        }) {
      final int status =
          keyed
              .post()
              .uri(java.net.URI.create(path))
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(BODY.formatted("r1"))
              .exchange()
              .returnResult(String.class)
              .getStatus()
              .value();
      org.assertj.core.api.Assertions.assertThat(status).as(path).isIn(400, 401, 404);
    }
  }
}
