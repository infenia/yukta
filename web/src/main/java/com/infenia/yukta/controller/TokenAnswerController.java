// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.TokenAnswerRequest;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.security.CallRateLimiter;
import com.infenia.yukta.service.input.token.TokenAnswerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers an input request with the one-time token of a delivery. This is the only {@code
 * /api/inputs} path that needs no API key ({@code ApiKeyFilter} lets exactly this path through):
 * the token stands for the tier the request was delivered to. Use HTTPS only.
 *
 * <p>Calls are limited per client address and per request. Behind a proxy the address is the
 * proxy's, so the per-request limit is the one that counts there.
 *
 * @since 0.1.0-alpha.4
 */
@Slf4j
@RestController
@RequestMapping("/api/inputs")
@Tag(name = "Input Requests API", description = "Answer an input request with a one-time token")
public class TokenAnswerController {

  /** The path, which the key filter lets through. */
  public static final String PATH = "/api/inputs/token-answer";

  /** Calls allowed a minute from one address. */
  private static final int PER_ADDRESS = 30;

  /** Calls allowed a minute for one request. */
  private static final int PER_REQUEST = 10;

  /** Seconds a limited caller waits. */
  private static final String RETRY_AFTER_SECONDS = "60";

  /** Answers with the token. */
  private final TokenAnswerService service;

  /** Limits calls per client address. */
  private final CallRateLimiter perAddress = new CallRateLimiter(PER_ADDRESS);

  /** Limits calls per request id. */
  private final CallRateLimiter perRequest = new CallRateLimiter(PER_REQUEST);

  /**
   * Creates the controller.
   *
   * @param service answers with the token
   */
  @Autowired
  public TokenAnswerController(final TokenAnswerService service) {
    this.service = service;
  }

  /**
   * Answers a request with a one-time token.
   *
   * @param body the token and the answer
   * @param exchange the call, for the client address
   * @return 200 when the answer was taken; 403 with one message for every kind of bad token; 429
   *     when the caller is limited
   */
  @PostMapping("/token-answer")
  @Operation(
      summary = "Answer an input request with a one-time token",
      description =
          "No API key. The token of a delivery lets its receiver answer once, until the tier's"
              + " deadline. An unknown request, a wrong tier, a bad, expired or used token all"
              + " answer 403 'Invalid or expired token'. 429 when called too often.")
  public Mono<ResponseEntity<ApiResponse<Object>>> answer(
      @Valid @RequestBody final TokenAnswerRequest body, final ServerWebExchange exchange) {
    if (!perAddress.tryAcquire(remote(exchange)) || !perRequest.tryAcquire(body.requestId())) {
      return Mono.just(
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
              .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
              .body(
                  ApiResponse.error(
                      HttpStatus.TOO_MANY_REQUESTS.value(),
                      HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                      "Too many requests",
                      PATH,
                      null)));
    }
    return service
        .answer(
            new TokenAnswerService.Answer(
                body.requestId(),
                body.tier(),
                body.expires(),
                body.token(),
                body.decision(),
                body.text(),
                body.data(),
                body.rationale()))
        .thenReturn(ResponseEntity.ok(ApiResponse.<Object>success(200, "Answer accepted", null)));
  }

  private static String remote(final ServerWebExchange exchange) {
    final InetSocketAddress address = exchange.getRequest().getRemoteAddress();
    return address == null ? "unknown" : address.getAddress().getHostAddress();
  }
}
