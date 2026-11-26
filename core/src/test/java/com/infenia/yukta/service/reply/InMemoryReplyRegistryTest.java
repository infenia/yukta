// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.reply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryReplyRegistryTest {

  private final InMemoryReplyRegistry replies = new InMemoryReplyRegistry();

  @Test
  void theFirstReplyReachesTheWaitingCaller() {
    final var answer = replies.open("k");

    assertThat(replies.reply("k", "first")).isTrue();
    assertThat(replies.reply("k", "second")).isFalse();
    StepVerifier.create(answer).expectNext("first").verifyComplete();
  }

  @Test
  void replyNobodyWaitsForIsDropped() {
    replies.open("k");
    replies.close("k");

    assertThat(replies.reply("k", "late")).isFalse();
    assertThat(replies.reply("other", "x")).isFalse();
    assertThat(replies.reply(null, "x")).isFalse();
    assertThat(replies.waiting()).isZero();
  }
}
