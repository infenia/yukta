// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.CommentRequired", "PMD.ShortVariable", "PMD.UnitTestShouldIncludeAssert"})
@NoArgsConstructor
class ExecutionEventFeedTest {

  private static ExecutionEvent event(final String id) {
    return new ExecutionEvent.WorkflowStatusChanged(id, "wf", "RUNNING", Instant.now());
  }

  @Test
  void events_everySubscriberGetsLaterEvents_andNotEarlierOnes() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    feed.publish(event("before"));

    final var a = StepVerifier.create(feed.events().take(1)).expectNextCount(1).expectComplete();
    final var b = StepVerifier.create(feed.events().take(1)).expectNextCount(1).expectComplete();
    final var verifierA = a.verifyLater();
    final var verifierB = b.verifyLater();
    feed.publish(event("after"));

    verifierA.verify();
    verifierB.verify();
  }

  @Test
  void publish_noSubscriber_isDropped() {
    new ExecutionEventFeed().publish(event("nobody"));
  }
}
