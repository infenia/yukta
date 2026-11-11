// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The hub remembers signals per supervisor and passes credential changes on. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.TooManyMethods"
})
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
  void cursorOlderThanWhatIsRememberedStartsWithEventsMissed() {
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
  void readerThatFallsBehindIsToldHowManySignalsItMissed() {
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
    assertThat(
            new AgentEngineConfiguration()
                .httpSupervisorFactory(
                    hub,
                    com.infenia.yukta.plugin.util.EgressPolicy.OPEN,
                    java.util.List.of(),
                    org.springframework.web.reactive.function.client.WebClient.builder(),
                    tools.jackson.databind.json.JsonMapper.builder().build())
                .type())
        .isEqualTo("http");
  }

  @Test
  void anOpenReviewOrDelegationIsAnsweredOnceAndOnlyInTime() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    channel.signal(tick(1));
    channel.signal(new Signal.Review("rv", null, Duration.ofSeconds(10)));
    channel.signal(new Signal.Review("late", null, Duration.ofNanos(1)));
    channel.signal(new Signal.Delegated("dl", null, "child", Map.of()));
    final Instant now = Instant.now();

    assertThat(channel.claimAnswer("verdict", "rv", now)).isTrue();
    assertThat(channel.claimAnswer("verdict", "rv", now)).isFalse(); // once
    assertThat(channel.claimAnswer("command", "rv", now)).isTrue(); // a command it caused
    assertThat(channel.claimAnswer("verdict", "dl", now)).isTrue();
    assertThat(channel.claimAnswer("verdict", "unknown", now)).isFalse();
    assertThat(channel.claimAnswer("verdict", "late", now.plusSeconds(60))).isFalse();
  }

  @Test
  void rememberedAnswersAreBoundedLikeTheSignals() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    for (int i = 0; i < AgentHub.REMEMBERED + 5; i++) {
      channel.signal(new Signal.Delegated("d" + i, null, "child", Map.of()));
      assertThat(channel.claimAnswer("verdict", "d" + i, Instant.now())).isTrue();
    }

    assertThat(channel.claimAnswer("verdict", "d" + (AgentHub.REMEMBERED + 4), Instant.now()))
        .isFalse();
  }

  @Test
  void negativeCursorMeansFromNowOnAndSkipsWhatWasSent() {
    final AgentHub.Channel channel = hub.open("s", () -> EngineReads.NONE);
    channel.signal(tick(1));

    StepVerifier.create(channel.signalsAfter(-1))
        .then(() -> channel.signal(tick(2)))
        .assertNext(s -> assertThat(s.cursor()).isEqualTo(2))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void theReadsOfASupervisorWithSeveralEnginesAreTheirUnion() {
    final AgentHub hub = new AgentHub();
    final AgentHub.Lease first = hub.lease("s", () -> reading("e1", Instant.EPOCH));
    final AgentHub.Lease second = hub.lease("s", () -> reading("e2", Instant.EPOCH.plusSeconds(5)));

    assertThat(first.channel()).isSameAs(second.channel());
    assertThat(first.channel().reads().executions(null, 10).collectList().block())
        .extracting(EngineReads.RunView::executionId)
        .containsExactly("e2", "e1");
    assertThat(first.channel().reads().executions(null, 1).collectList().block()).hasSize(1);
    first.close();
    second.close();
  }

  private static EngineReads reading(final String executionId, final Instant started) {
    return new EngineReads() {
      @Override
      public Flux<RunView> executions(final String status, final int limit) {
        return Flux.just(new RunView("s", executionId, "w", "RUNNING", started));
      }

      @Override
      public Flux<InputRequest> openInputs(final int limit) {
        return Flux.empty();
      }
    };
  }
}
