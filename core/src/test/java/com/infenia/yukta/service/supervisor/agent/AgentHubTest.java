// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** The hub remembers signals per supervisor and passes credential changes on. */
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
class AgentHubTest {

  private final AgentHub hub = new AgentHub();

  private static Signal tick(final long second) {
    return new Signal.Tick(Instant.ofEpochSecond(second));
  }

  @Test
  void signalsAfterReplaysRememberedOnesThenLiveOnes() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    assertThat(channel.cursor()).isZero();
    channel.signal(tick(1));
    channel.signal(tick(2));
    assertThat(channel.cursor()).isEqualTo(2);

    StepVerifier.create(channel.signalsAfter(1))
        .assertNext(s -> assertThat(s.cursor()).isEqualTo(2))
        .then(() -> channel.signal(tick(3)))
        .assertNext(s -> assertThat(s.cursor()).isEqualTo(3))
        .thenCancel()
        .verify(Duration.ofSeconds(5));

    StepVerifier.create(channel.signalsAfter(3))
        .then(() -> channel.signal(tick(4)))
        .assertNext(s -> assertThat(s.cursor()).isEqualTo(4))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void cursorAheadOfTheStreamSkipsSignalsUpToIt() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);

    StepVerifier.create(channel.signalsAfter(1))
        .then(() -> channel.signal(tick(1)))
        .then(() -> channel.signal(tick(2)))
        .assertNext(s -> assertThat(s.cursor()).isEqualTo(2))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void aCursorOlderThanWhatIsRememberedStartsWithEventsMissed() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    for (int i = 0; i < AgentHub.REMEMBERED + 5; i++) {
      channel.signal(tick(i));
    }

    StepVerifier.create(channel.signalsAfter(0).take(2))
        .assertNext(s -> assertThat(missed(s)).isEqualTo(5))
        .assertNext(s -> assertThat(s.cursor()).isEqualTo(6))
        .verifyComplete();
  }

  @Test
  void aReaderThatFallsBehindIsToldHowManySignalsItMissed() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    final int sent = AgentHub.LIVE_BUFFER * 3;

    StepVerifier.create(channel.signalsAfter(-1), 0)
        .then(
            () -> {
              for (int i = 0; i < sent; i++) {
                channel.signal(tick(i));
              }
            })
        .thenRequest(Long.MAX_VALUE)
        .then(
            () -> {
              channel.signal(tick(sent));
              channel.complete();
            })
        .recordWith(java.util.ArrayList::new)
        .thenConsumeWhile(s -> true)
        .consumeRecordedWith(
            all -> {
              final long gaps =
                  all.stream().mapToLong(AgentHubTest::missed).filter(n -> n > 0).sum();
              final long delivered = all.stream().filter(s -> missed(s) < 0).count();
              // nothing vanishes without a word: each signal is delivered or counted as missed
              assertThat(gaps).isPositive();
              assertThat(delivered + gaps).isEqualTo(sent + 1);
            })
        .verifyComplete();
  }

  private static long missed(final AgentHub.Sequenced sequenced) {
    return sequenced.signal() instanceof Signal.Event event
            && event.event() instanceof ExecutionEvent.EventsMissed gap
        ? gap.count()
        : -1;
  }

  @Test
  void onlyTheNewestSignalsAreRemembered() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    for (int i = 0; i <= AgentHub.REMEMBERED; i++) {
      channel.signal(new Signal.Event("e" + i, "c", null));
    }

    assertThat(channel.sent("e0")).isFalse();
    assertThat(channel.sent("e1")).isTrue();
    assertThat(channel.sent("e" + AgentHub.REMEMBERED)).isTrue();
  }

  @Test
  void sentKnowsTheIdsOfEventsReviewsAndDelegations() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    channel.signal(tick(1));
    channel.signal(new Signal.Event("ev", "c", null));
    channel.signal(new Signal.Review("rv", null, Duration.ofSeconds(1)));
    channel.signal(new Signal.Delegated("dl", null, "child", Map.of()));

    assertThat(channel.sent("ev")).isTrue();
    assertThat(channel.sent("rv")).isTrue();
    assertThat(channel.sent("dl")).isTrue();
    assertThat(channel.sent("other")).isFalse();
  }

  @Test
  void credentialChangesReachSubscribers() {
    StepVerifier.create(hub.credentialChanges())
        .then(() -> hub.credentialChanged("s"))
        .expectNext("s")
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void configurationOffersTheHttpType() {
    assertThat(new AgentEngineConfiguration().httpSupervisorFactory(hub).type()).isEqualTo("http");
  }
}
