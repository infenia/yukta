// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.gateway.ReplyGateway;
import com.infenia.yukta.service.reply.InMemoryReplyRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Starts a run and, with {@code ?wait=<seconds>}, holds the call until the run's {@code REPLY}
 * terminal answers: 200 with the reply, or 202 with only the execution id when the wait (capped by
 * {@code yukta.reply.max-wait}) ends first. Without a wait the call answers 202 at once.
 */
@Component
public class ReplyWait {

  /** The open waits. */
  private final InMemoryReplyRegistry replies;

  /** Longest a call may wait. */
  private final Duration maxWait;

  /**
   * Creates the helper.
   *
   * @param replies the open waits
   * @param maxWait {@code yukta.reply.max-wait}, the longest a call may wait (default 30 s)
   */
  public ReplyWait(
      final InMemoryReplyRegistry replies,
      @Value("${yukta.reply.max-wait:30s}") final Duration maxWait) {
    this.replies = replies;
    this.maxWait = maxWait;
  }

  /**
   * Starts the run and answers the call.
   *
   * @param waitSeconds the {@code wait} query parameter, or null
   * @param payload the start payload
   * @param starter starts the run with a payload and gives its execution id
   * @param accepted the message of a 202 answer
   * @return 200 with the reply, or 202
   */
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> start(
      @Nullable final Integer waitSeconds,
      final Map<String, Object> payload,
      final Function<Map<String, Object>, Mono<String>> starter,
      final String accepted) {
    if (waitSeconds == null || waitSeconds <= 0) {
      return starter.apply(payload).map(id -> accepted(id, accepted));
    }
    final Duration wait =
        Duration.ofSeconds(waitSeconds).compareTo(maxWait) < 0
            ? Duration.ofSeconds(waitSeconds)
            : maxWait;
    final String key = UUID.randomUUID().toString();
    final Mono<Object> reply = replies.open(key);
    // a HashMap: a start payload may hold null values, which ConcurrentHashMap refuses
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    final Map<String, Object> keyed = new HashMap<>(payload);
    keyed.put(ReplyGateway.REPLY_TO_KEY, key);
    return starter
        .apply(keyed)
        .flatMap(id ->
            reply
                .map(answer -> replied(id, answer))
                .switchIfEmpty(Mono.fromSupplier(() -> replied(id, null)))
                .timeout(wait, Mono.fromSupplier(() -> accepted(id, accepted))))
        .doFinally(_ -> replies.close(key));
  }

  private static ResponseEntity<ApiResponse<WorkflowStartResponse>> replied(
      final String id, @Nullable final Object answer) {
    return ResponseEntity.ok(
        ApiResponse.success(200, "Run replied", new WorkflowStartResponse(id, answer)));
  }

  private static ResponseEntity<ApiResponse<WorkflowStartResponse>> accepted(
      final String id, final String message) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.success(202, message, new WorkflowStartResponse(id)));
  }
}
