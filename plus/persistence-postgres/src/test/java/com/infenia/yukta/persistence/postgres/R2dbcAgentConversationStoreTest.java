// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.conversation;
import static com.infenia.yukta.service.agent.AgentTestSupport.ended;
import static com.infenia.yukta.service.agent.AgentTestSupport.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.agent.AgentConversationStore;
import com.infenia.yukta.service.agent.AgentConversationStoreContract;
import com.infenia.yukta.service.agent.ConversationFilter;
import com.infenia.yukta.service.agent.EventFilter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The shared store contract against a real PostgreSQL, and what a restart does. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
@Testcontainers(disabledWithoutDocker = true)
class R2dbcAgentConversationStoreTest extends AgentConversationStoreContract {

  private static final String DATA = "data";
  private static final String CHUNK = "AAAA";

  private DatabaseClient db;

  @Override
  protected AgentConversationStore create(
      final int conversations, final int events, final long ringBytes) {
    db = PostgresTestSupport.prepared();
    db.sql("DELETE FROM agent_conversation").fetch().rowsUpdated().block();
    return new R2dbcAgentConversationStore(
        db, JsonMapper.builder().build(), conversations, events, ringBytes);
  }

  @Test
  void savedDataSurvivesRestartExceptTerminalOutput() {
    final AgentConversationStore first = create(10, 10, 1000);
    first.save(conversation("a")).block();
    final AgentEvent message =
        java.util.Objects.requireNonNull(first.append(event("a", AgentEventKind.MESSAGE)).block())
            .event();
    first
        .append(
            new AgentEvent(
                0,
                "a",
                NOW,
                AgentEventKind.TERMINAL,
                null,
                java.util.Map.of("screen", "hello", "cols", 80, "rows", 24)))
        .block();
    first
        .append(
            new AgentEvent(
                0,
                "a",
                NOW,
                AgentEventKind.TERMINAL,
                null,
                java.util.Map.of(DATA, CHUNK, "cols", 80, "rows", 24)))
        .block();

    final AgentConversationStore restarted =
        new R2dbcAgentConversationStore(db, JsonMapper.builder().build(), 10, 10, 1000);

    assertThat(restarted.epoch()).isEqualTo(first.epoch());
    assertThat(restarted.find("a").block()).isNotNull();
    assertThat(java.util.Objects.requireNonNull(restarted.latestScreen("a").block()).data())
        .containsEntry("screen", "hello");
    assertThat(restarted.events(EventFilter.forConversation("a"), 0, 10).collectList().block())
        .extracting(AgentEvent::kind)
        .containsExactly(AgentEventKind.MESSAGE, AgentEventKind.TERMINAL)
        .doesNotContain(AgentEventKind.NOTICE);
    assertThat(
            java.util.Objects.requireNonNull(
                    restarted.events(EventFilter.forConversation("a"), 0, 10).blockFirst())
                .seq())
        .isEqualTo(message.seq());
  }

  @Test
  void conversationLeftOpenByRestartIsEndedAsFailed() {
    final R2dbcAgentConversationStore store = (R2dbcAgentConversationStore) create(10, 10, 1000);
    store.save(conversation("open")).block();
    store.save(conversation("waiting").withState(AgentState.WAITING)).block();
    store.save(ended("done", NOW)).block();

    assertThat(store.endOpenConversationsNow()).isEqualTo(2);

    final AgentConversation open = java.util.Objects.requireNonNull(store.find("open").block());
    assertThat(open.state()).isEqualTo(AgentState.ENDED);
    assertThat(open.outcome()).isEqualTo("failure");
    assertThat(open.reason()).isEqualTo("server restarted");
    assertThat(open.endedAt()).isNotNull();
    assertThat(java.util.Objects.requireNonNull(store.find("done").block()).outcome())
        .isEqualTo("success");
    assertThat(store.events(EventFilter.forConversation("open"), 0, 10).collectList().block())
        .singleElement()
        .satisfies(e -> assertThat(e.kind()).isEqualTo(AgentEventKind.STATE));
    assertThat(store.endOpenConversationsNow()).isZero();
    assertThat(store.list(ConversationFilter.all()).count().block()).isEqualTo(3);
  }

  @Test
  void everyBoundMustBeAtLeastOne() {
    final DatabaseClient client = org.mockito.Mockito.mock(DatabaseClient.class);
    final JsonMapper mapper = JsonMapper.builder().build();

    assertThatThrownBy(() -> new R2dbcAgentConversationStore(client, mapper, 0, 10, 1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new R2dbcAgentConversationStore(client, mapper, 10, 0, 1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new R2dbcAgentConversationStore(client, mapper, 10, 10, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AgentEvent terminal(
      final String conversation, final java.util.Map<String, Object> data) {
    return new AgentEvent(0, conversation, NOW, AgentEventKind.TERMINAL, null, data);
  }

  @Test
  void ringOutputAfterTheCursorIsReturned_evenWhenTheCursorPassedTheScreen() {
    final AgentConversationStore store = create(10, 10, 1000);
    store.save(conversation("a")).block();
    final long screen =
        java.util.Objects.requireNonNull(
                store.append(terminal("a", java.util.Map.of("screen", "old"))).block())
            .event()
            .seq();
    final long piece =
        java.util.Objects.requireNonNull(
                store.append(terminal("a", java.util.Map.of(DATA, CHUNK))).block())
            .event()
            .seq();

    // the cursor is past the screen: the screen is not sent again, the newer output is
    assertThat(store.events(EventFilter.forConversation("a"), screen, 10).collectList().block())
        .extracting(AgentEvent::seq)
        .containsExactly(piece);
  }

  @Test
  void ringPiecesAreFilteredBySequenceAndKind_andNoScreenIsEmpty() {
    final AgentConversationStore store = create(10, 10, 1000);
    store.save(conversation("a")).block();
    store.append(event("a", AgentEventKind.MESSAGE)).block();
    final long piece =
        java.util.Objects.requireNonNull(
                store
                    .append(
                        new AgentEvent(
                            0,
                            "a",
                            NOW,
                            AgentEventKind.TERMINAL,
                            null,
                            java.util.Map.of(DATA, CHUNK, "cols", 80, "rows", 24)))
                    .block())
            .event()
            .seq();

    assertThat(
            store
                .events(
                    new EventFilter(
                        "a", null, null, null, java.util.Set.of(AgentEventKind.MESSAGE)),
                    0,
                    10)
                .collectList()
                .block())
        .extracting(AgentEvent::kind)
        .containsExactly(AgentEventKind.MESSAGE);
    assertThat(store.events(EventFilter.forConversation("a"), piece, 10).collectList().block())
        .isEmpty();
    assertThat(store.latestScreen("a").block()).isNull();
  }

  @Test
  void ringPiecesAtOrBeforeTheCursorAreNotReturned() {
    final AgentConversationStore store = create(10, 10, 1000);
    store.save(conversation("a")).block();
    final long first =
        java.util.Objects.requireNonNull(
                store.append(terminal("a", java.util.Map.of(DATA, CHUNK))).block())
            .event()
            .seq();
    final long second =
        java.util.Objects.requireNonNull(
                store.append(terminal("a", java.util.Map.of(DATA, CHUNK))).block())
            .event()
            .seq();

    assertThat(store.events(EventFilter.forConversation("a"), first, 10).collectList().block())
        .extracting(AgentEvent::seq)
        .containsExactly(second);
  }

  @Test
  void ringOutputBehindTheCursorIsSkippedEvenWhenNewerEventsExist() {
    final AgentConversationStore store = create(10, 10, 1000);
    store.save(conversation("a")).block();
    store.append(terminal("a", java.util.Map.of(DATA, CHUNK))).block();
    final long message =
        java.util.Objects.requireNonNull(store.append(event("a", AgentEventKind.MESSAGE)).block())
            .event()
            .seq();

    assertThat(store.events(EventFilter.forConversation("a"), message, 10).collectList().block())
        .isEmpty();
  }

  @Test
  void terminalKindFilterReturnsTerminalOutput() {
    final AgentConversationStore store = create(10, 10, 1000);
    store.save(conversation("a")).block();
    store.append(event("a", AgentEventKind.MESSAGE)).block();
    final long piece =
        java.util.Objects.requireNonNull(
                store.append(terminal("a", java.util.Map.of(DATA, CHUNK))).block())
            .event()
            .seq();

    assertThat(
            store
                .events(
                    new EventFilter(
                        "a", null, null, null, java.util.Set.of(AgentEventKind.TERMINAL)),
                    0,
                    10)
                .collectList()
                .block())
        .extracting(AgentEvent::seq)
        .containsExactly(piece);
  }

  /** The count of stored events that the bound is checked against, kept on the conversation. */
  /* default */ @org.junit.jupiter.api.Nested
  class CountedEvents {

    @Test
    void noticesFromCallersDoNotCountTowardsTheBound() {
      final AgentConversationStore store = create(10, 2, 1000);
      store.save(conversation("a")).block();
      store.append(event("a", AgentEventKind.MESSAGE)).block();
      store.append(event("a", AgentEventKind.NOTICE)).block();
      store.append(event("a", AgentEventKind.NOTICE)).block();
      store.append(event("a", AgentEventKind.MESSAGE)).block();

      // two messages and two notices: at the bound of two, so nothing is dropped
      assertThat(store.events(EventFilter.forConversation("a"), 0, 50).collectList().block())
          .filteredOn(e -> e.kind() == AgentEventKind.MESSAGE)
          .hasSize(2);
    }

    @Test
    void trimWithNothingToDropWritesNoNotice() {
      final AgentConversationStore store = create(10, 3, 1000);
      store.save(conversation("a")).block();
      // the counter is over the bound, but no event that may be dropped is stored
      db.sql("UPDATE agent_conversation SET events = 5 WHERE id = 'a'")
          .fetch()
          .rowsUpdated()
          .block();

      final AgentConversationStore.AppendResult appended =
          store.append(event("a", AgentEventKind.NOTICE)).block();

      assertThat(java.util.Objects.requireNonNull(appended).notice()).isNull();
    }

    @Test
    void zeroLimitReturnsNothing() {
      final AgentConversationStore store = create(10, 10, 1000);
      store.save(conversation("a")).block();
      store.append(event("a", AgentEventKind.MESSAGE)).block();

      assertThat(store.events(EventFilter.forConversation("a"), 0, 0).collectList().block())
          .isEmpty();
    }

    @Test
    void theCounterStaysExactOverManyTrims() {
      final AgentConversationStore store = create(10, 3, 1000);
      store.save(conversation("a")).block();
      for (int i = 0; i < 10; i++) {
        store.append(event("a", AgentEventKind.MESSAGE)).block();
      }

      // three events kept, the counter says three, and one notice says some were dropped
      assertThat(
              db.sql("SELECT events FROM agent_conversation WHERE id = 'a'")
                  .map(row -> row.get(0, Integer.class))
                  .one()
                  .block())
          .isEqualTo(3);
      assertThat(store.events(EventFilter.forConversation("a"), 0, 50).collectList().block())
          .extracting(AgentEvent::kind)
          .containsExactly(
              AgentEventKind.NOTICE,
              AgentEventKind.MESSAGE,
              AgentEventKind.MESSAGE,
              AgentEventKind.MESSAGE);
    }
  }
}
