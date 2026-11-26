// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.gateway.ReplyGateway;
import com.infenia.yukta.service.reply.InMemoryReplyRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReplyWaitTest {

  private final InMemoryReplyRegistry replies = new InMemoryReplyRegistry();
  private final ReplyWait replyWait = new ReplyWait(replies, Duration.ofSeconds(2));
  private final AtomicReference<Map<String, Object>> started = new AtomicReference<>();

  /** Starts a run that answers with the reply, or not at all when it is null. */
  private Mono<String> run(final Map<String, Object> payload, final Object reply) {
    started.set(payload);
    if (reply != null) {
      replies.reply(String.valueOf(payload.get(ReplyGateway.REPLY_TO_KEY)), reply);
    }
    return Mono.just("e1");
  }

  @Test
  void withoutWaitItAnswers202AtOnceAndPassesNoKey() {
    StepVerifier.create(replyWait.start(null, Map.of("a", 1), p -> run(p, null), "ok"))
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
          assertThat(response.getBody().data().reply()).isNull();
        })
        .verifyComplete();
    assertThat(started.get()).isEqualTo(Map.of("a", 1));
    StepVerifier.create(replyWait.start(0, Map.of(), p -> run(p, null), "ok"))
        .assertNext(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED))
        .verifyComplete();
  }

  @Test
  void replyInTimeAnswers200WithIt() {
    StepVerifier.create(replyWait.start(5, Map.of("a", 1), p -> run(p, "done"), "ok"))
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(response.getBody().data().executionId()).isEqualTo("e1");
          assertThat(response.getBody().data().reply()).isEqualTo("done");
        })
        .verifyComplete();
    assertThat(started.get()).containsEntry("a", 1).containsKey(ReplyGateway.REPLY_TO_KEY);
    assertThat(replies.waiting()).isZero();
  }

  @Test
  void emptyReplyIsStillReply() {
    StepVerifier.create(replyWait.start(5, Map.of(), this::replyEmpty, "ok"))
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(response.getBody().data().reply()).isNull();
        })
        .verifyComplete();
  }

  private Mono<String> replyEmpty(final Map<String, Object> payload) {
    replies.reply(String.valueOf(payload.get(ReplyGateway.REPLY_TO_KEY)), null);
    return Mono.just("e1");
  }

  @Test
  void noReplyWithinTheCappedWaitAnswers202() {
    StepVerifier.withVirtualTime(() -> replyWait.start(600, Map.of(), p -> run(p, null), "ok"))
        .thenAwait(Duration.ofSeconds(2))
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
          assertThat(response.getBody().message()).isEqualTo("ok");
        })
        .verifyComplete();
    assertThat(replies.waiting()).isZero();
  }

  @Test
  void failedStartClosesTheWait() {
    StepVerifier.create(
            replyWait.start(5, Map.of(), p -> Mono.error(new IllegalStateException("x")), "ok"))
        .verifyError(IllegalStateException.class);
    assertThat(replies.waiting()).isZero();
  }
}
