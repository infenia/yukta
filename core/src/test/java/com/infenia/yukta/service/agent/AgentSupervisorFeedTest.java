// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** The bridge from conversations to the feed supervisors read. */
@NoArgsConstructor
class AgentSupervisorFeedTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  private final AgentConversationService service = mock(AgentConversationService.class);
  private final AgentConversationStore store = mock(AgentConversationStore.class);
  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final Sinks.Many<AgentChange> changes = Sinks.many().multicast().directBestEffort();

  private static AgentConversation conversation(final AgentState state) {
    return new AgentConversation(
        "c-1",
        "e-1",
        "e-1",
        "s-1",
        "w-1",
        "n-1",
        "m-1",
        "claude-code",
        AgentProtocol.JSONL,
        null,
        Set.of(),
        state,
        null,
        null,
        0,
        0,
        new Audience(List.of("supervisor:*")),
        NOW,
        NOW,
        null,
        1);
  }

  @Test
  void everyChangeOfConversationIsEventOfItsRunAndNode() {
    when(service.changes()).thenReturn(changes.asFlux());
    when(store.list(any())).thenReturn(Flux.empty());
    final List<ExecutionEvent> seen = new ArrayList<>();
    feed.events().subscribe(seen::add);
    final AgentSupervisorFeed bridge =
        new AgentSupervisorFeed(service, store, feed, Clock.fixed(NOW, ZoneOffset.UTC));

    bridge.start();
    changes.tryEmitNext(new AgentChange.ConversationChanged(conversation(AgentState.RUNNING)));
    bridge.stop();

    assertThat(seen)
        .singleElement()
        .isInstanceOfSatisfying(
            ExecutionEvent.AgentConversationChanged.class,
            event -> {
              assertThat(event.executionId()).isEqualTo("e-1");
              assertThat(event.sessionId()).isEqualTo("s-1");
              assertThat(event.nodeId()).isEqualTo("n-1");
              assertThat(event.conversation().state()).isEqualTo(AgentState.RUNNING);
              assertThat(event.timestamp()).isEqualTo(NOW);
            });
    assertThat(bridge.isRunning()).isFalse();
  }
}
