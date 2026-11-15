// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.conversation;
import static com.infenia.yukta.service.agent.AgentTestSupport.ended;
import static com.infenia.yukta.service.agent.AgentTestSupport.event;
import static com.infenia.yukta.service.agent.AgentTestSupport.output;
import static com.infenia.yukta.service.agent.AgentTestSupport.screen;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.agent.AgentConversationStore.AppendResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** The in-memory store: one sequence, two bounds, a ring for terminal output. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class InMemoryAgentConversationStoreTest {

  /** Four bytes of terminal output, in base64. */
  private static final String FOUR_BYTES = "AAAAAA==";

  private static InMemoryAgentConversationStore store(final int conversations, final int events) {
    return new InMemoryAgentConversationStore(conversations, events, 10);
  }

  private static List<AgentEvent> events(final AgentConversationStore store, final String id) {
    return store.events(EventFilter.forConversation(id), 0, 1000).collectList().block();
  }

  @Test
  void theSequenceStrictlyIncreasesOverConversationsAndEvents() {
    final InMemoryAgentConversationStore store = store(10, 10);

    final long first =
        java.util.Objects.requireNonNull(store.save(conversation("a")).block()).seq();
    final long second =
        java.util.Objects.requireNonNull(store.append(event("a", AgentEventKind.MESSAGE)).block())
            .event()
            .seq();
    final long third =
        java.util.Objects.requireNonNull(store.save(conversation("a").withTurn(1)).block()).seq();
    final long fourth =
        java.util.Objects.requireNonNull(store.save(conversation("b")).block()).seq();
    final long fifth =
        java.util.Objects.requireNonNull(store.append(event("b", AgentEventKind.TOOL)).block())
            .event()
            .seq();

    assertThat(List.of(first, second, third, fourth, fifth)).isSorted().doesNotHaveDuplicates();
    assertThat(first).isPositive();
  }

  @Test
  void savingAgainReplacesTheRecordOfTheSameId() {
    final InMemoryAgentConversationStore store = store(10, 10);
    store.save(conversation("a")).block();
    store.save(conversation("a").withTurn(3)).block();

    StepVerifier.create(store.find("a"))
        .assertNext(c -> assertThat(c.turn()).isEqualTo(3))
        .verifyComplete();
    StepVerifier.create(store.list(ConversationFilter.all())).expectNextCount(1).verifyComplete();
    StepVerifier.create(store.find("missing")).verifyComplete();
  }

  @Test
  void theEpochIsStableForOneStoreAndDiffersBetweenStores() {
    final InMemoryAgentConversationStore store = store(10, 10);

    assertThat(store.epoch()).isEqualTo(store.epoch()).isNotBlank();
    assertThat(store(10, 10).epoch()).isNotEqualTo(store.epoch());
  }

  @Test
  void endedConversationsGoFirstAndTheOneThatEndedLongestAgoGoesFirst() {
    final InMemoryAgentConversationStore store = store(2, 10);
    store.save(conversation("live")).block();
    store.save(ended("older", NOW.minusSeconds(60))).block();
    store.save(ended("newer", NOW.minusSeconds(10))).block();

    StepVerifier.create(store.find("older")).verifyComplete();
    StepVerifier.create(store.find("newer")).expectNextCount(1).verifyComplete();
    StepVerifier.create(store.find("live")).expectNextCount(1).verifyComplete();
  }

  @Test
  void liveConversationIsNeverEvictedEvenOverTheBound() {
    final InMemoryAgentConversationStore store = store(1, 10);
    store.save(conversation("a")).block();
    store.save(conversation("b")).block();
    store.save(conversation("c").withState(AgentState.WAITING)).block();

    StepVerifier.create(store.list(ConversationFilter.all())).expectNextCount(3).verifyComplete();
  }

  @Test
  void theConversationJustSavedSurvivesEvenWhenItIsTheOldestEnded() {
    final InMemoryAgentConversationStore store = store(1, 10);
    store.save(ended("new-but-ended-long-ago", NOW.minusSeconds(999))).block();
    store.save(ended("other", NOW)).block();

    StepVerifier.create(store.find("other")).expectNextCount(1).verifyComplete();
    StepVerifier.create(store.find("new-but-ended-long-ago")).verifyComplete();
  }

  @Test
  void theOldestEventsGoFirstAndOneNoticeSaysSoOnce() {
    final InMemoryAgentConversationStore store = store(10, 3);
    store.save(conversation("a")).block();

    final List<AppendResult> results =
        List.of(1, 2, 3, 4, 5).stream()
            .map(i -> store.append(event("a", AgentEventKind.MESSAGE)).block())
            .toList();

    assertThat(results.subList(0, 3)).allSatisfy(result -> assertThat(result.notice()).isNull());
    assertThat(results.get(3).notice()).isNotNull();
    assertThat(results.get(3).notice().kind()).isEqualTo(AgentEventKind.NOTICE);
    assertThat(results.get(3).notice().data())
        .containsEntry("level", "WARN")
        .containsEntry("text", InMemoryAgentConversationStore.DROPPED_NOTICE);
    assertThat(results.get(3).notice().seq()).isGreaterThan(results.get(3).event().seq());
    assertThat(results.get(4).notice()).isNull();
    final List<AgentEvent> kept = events(store, "a");
    assertThat(kept).extracting(AgentEvent::kind).containsOnlyOnce(AgentEventKind.NOTICE);
    assertThat(kept).hasSize(4).extracting(AgentEvent::seq).isSorted();
    assertThat(kept.stream().filter(e -> e.kind() == AgentEventKind.MESSAGE).map(AgentEvent::seq))
        .containsExactly(
            results.get(2).event().seq(),
            results.get(3).event().seq(),
            results.get(4).event().seq());
  }

  @Test
  void terminalOutputLivesInTheRingAndDoesNotCountAsEvents() {
    final InMemoryAgentConversationStore store = store(10, 2);
    store.save(conversation("a")).block();
    store.append(event("a", AgentEventKind.MESSAGE)).block();
    final List<AppendResult> chunks =
        List.of(1, 2, 3, 4, 5).stream()
            .map(i -> store.append(output("a", FOUR_BYTES)).block())
            .toList();

    assertThat(chunks).allSatisfy(result -> assertThat(result.notice()).isNull());
    final List<AgentEvent> kept = events(store, "a");
    assertThat(kept.stream().filter(e -> e.kind() == AgentEventKind.TERMINAL).map(AgentEvent::seq))
        .containsExactly(chunks.get(3).event().seq(), chunks.get(4).event().seq());
    assertThat(kept.stream().filter(e -> e.kind() == AgentEventKind.MESSAGE)).hasSize(1);
  }

  @Test
  void singleChunkLargerThanTheRingIsKept() {
    final InMemoryAgentConversationStore store = new InMemoryAgentConversationStore(10, 10, 2);
    store.save(conversation("a")).block();
    store.append(output("a", FOUR_BYTES)).block();

    assertThat(events(store, "a")).hasSize(1);
  }

  @Test
  void onlyTheLatestScreenIsKeptAndItIsReadApart() {
    final InMemoryAgentConversationStore store = store(10, 10);
    store.save(conversation("a")).block();
    store.append(screen("a", "first")).block();
    final AgentEvent latest =
        java.util.Objects.requireNonNull(store.append(screen("a", "second")).block()).event();

    StepVerifier.create(store.latestScreen("a")).expectNext(latest).verifyComplete();
    assertThat(events(store, "a")).containsExactly(latest);
    StepVerifier.create(store.latestScreen("missing")).verifyComplete();
    StepVerifier.create(store.latestScreen("b")).verifyComplete();
  }

  @Test
  void eventsAreFilteredByRunKindAndAfterSeqAndLimited() {
    final InMemoryAgentConversationStore store = store(10, 10);
    store.save(conversation("a")).block();
    store.save(conversation("b").withExecutionId("exec-2").withRootExecutionId("root-2")).block();
    final AgentEvent a1 =
        java.util.Objects.requireNonNull(store.append(event("a", AgentEventKind.MESSAGE)).block())
            .event();
    final AgentEvent b1 =
        java.util.Objects.requireNonNull(store.append(event("b", AgentEventKind.TOOL)).block())
            .event();
    final AgentEvent a2 =
        java.util.Objects.requireNonNull(store.append(event("a", AgentEventKind.TOOL)).block())
            .event();

    final EventFilter root1 = new EventFilter(null, null, null, "root-1", null);
    final EventFilter exec2 = new EventFilter(null, null, "exec-2", null, null);
    final EventFilter tools =
        new EventFilter(null, "session-1", null, null, Set.of(AgentEventKind.TOOL));

    assertThat(store.events(root1, 0, 10).collectList().block()).containsExactly(a1, a2);
    assertThat(store.events(exec2, 0, 10).collectList().block()).containsExactly(b1);
    assertThat(store.events(tools, 0, 10).collectList().block()).containsExactly(b1, a2);
    assertThat(store.events(root1, a1.seq(), 10).collectList().block()).containsExactly(a2);
    assertThat(store.events(tools, 0, 1).collectList().block()).containsExactly(b1);
    assertThat(store.events(tools, 0, -1).collectList().block()).isEmpty();
  }

  @Test
  void conversationsAreListedNewestFirstFilteredAndLimited() {
    final InMemoryAgentConversationStore store = store(10, 10);
    store.save(conversation("old").withStartedAt(NOW.minusSeconds(30))).block();
    store
        .save(conversation("mid").withStartedAt(NOW.minusSeconds(20)).withSessionId("other"))
        .block();
    store
        .save(conversation("new").withStartedAt(NOW.minusSeconds(10)).withState(AgentState.IDLE))
        .block();

    assertThat(ids(store, ConversationFilter.all())).containsExactly("new", "mid", "old");
    assertThat(ids(store, new ConversationFilter("session-1", null, null, null, 10)))
        .containsExactly("new", "old");
    assertThat(ids(store, new ConversationFilter(null, null, null, Set.of(AgentState.IDLE), 10)))
        .containsExactly("new");
    assertThat(ids(store, new ConversationFilter(null, "exec-1", "root-1", null, 2)))
        .containsExactly("new", "mid");
  }

  @Test
  void purgeRemovesEndedConversationsBeforeTheCutOffAndKeepsTheRest() {
    final InMemoryAgentConversationStore store = store(10, 10);
    store.save(conversation("live")).block();
    store.save(ended("old", NOW.minus(Duration.ofDays(8)))).block();
    store.save(ended("recent", NOW.minus(Duration.ofDays(1)))).block();
    store.append(event("old", AgentEventKind.MESSAGE)).block();

    StepVerifier.create(store.purge(NOW.minus(Duration.ofDays(7)))).expectNext(1L).verifyComplete();

    StepVerifier.create(store.find("old")).verifyComplete();
    assertThat(ids(store, ConversationFilter.all())).containsExactlyInAnyOrder("live", "recent");
    StepVerifier.create(store.purge(Instant.MIN.plusSeconds(1))).expectNext(0L).verifyComplete();
  }

  @Test
  void eventForAnUnknownConversationFails() {
    StepVerifier.create(store(10, 10).append(event("nope", AgentEventKind.MESSAGE)))
        .expectErrorMessage("Unknown conversation 'nope'")
        .verify();
  }

  @Test
  void everyBoundMustBeAtLeastOne() {
    assertThatThrownBy(() -> new InMemoryAgentConversationStore(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryAgentConversationStore(1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryAgentConversationStore(1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConversationFilter(null, null, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<String> ids(
      final AgentConversationStore store, final ConversationFilter filter) {
    return store.list(filter).map(AgentConversation::id).collectList().block();
  }
}
