// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.conversation;
import static com.infenia.yukta.service.agent.AgentTestSupport.ended;
import static com.infenia.yukta.service.agent.AgentTestSupport.event;
import static org.assertj.core.api.Assertions.assertThat;

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
                java.util.Map.of("data", "AAAA", "cols", 80, "rows", 24)))
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
                            java.util.Map.of("data", "AAAA", "cols", 80, "rows", 24)))
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
  void everyBoundMustBeAtLeastOne() {
    final DatabaseClient client = org.mockito.Mockito.mock(DatabaseClient.class);
    final JsonMapper mapper = JsonMapper.builder().build();
    final java.time.Clock clock = java.time.Clock.systemUTC();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new R2dbcAgentConversationStore(client, mapper, 0, 1, 1, clock))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new R2dbcAgentConversationStore(client, mapper, 1, 0, 1, clock))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new R2dbcAgentConversationStore(client, mapper, 1, 1, 0, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
