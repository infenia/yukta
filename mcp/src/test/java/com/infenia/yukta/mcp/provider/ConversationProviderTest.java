// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.agent.AgentConversationQueries;
import com.infenia.yukta.service.agent.AgentSteering;
import com.infenia.yukta.service.agent.AgentSteeringException;
import com.infenia.yukta.service.control.SupervisedControl;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for {@link ConversationProvider}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class ConversationProviderTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  private final AgentConversationQueries queries = mock(AgentConversationQueries.class);
  private final AgentSteering steering = mock(AgentSteering.class);
  private final SupervisedControl control = mock(SupervisedControl.class);
  private final ConversationProvider provider =
      new ConversationProvider(queries, steering, control);

  private static AgentConversation conversation(final Set<String> capabilities) {
    return new AgentConversation(
        "c-1",
        "e-1",
        "e-1",
        "s-1",
        "w-1",
        "n-1",
        "m-1",
        "terminal",
        AgentProtocol.PTY,
        null,
        capabilities,
        AgentState.RUNNING,
        null,
        null,
        0,
        0,
        new Audience(List.of("role:OPERATOR")),
        NOW,
        NOW,
        null,
        1);
  }

  private void found() {
    when(queries.require(eq("c-1"), any()))
        .thenReturn(Mono.just(conversation(Set.of(AgentCapability.MESSAGE))));
  }

  @Test
  void listsConversationsOfTheSessionAndShowsWhatEachCanDo() {
    when(queries.list(any(), anyInt()))
        .thenReturn(Flux.just(conversation(Set.of(AgentCapability.TERMINAL, "message"))));

    final var list = provider.list("s-1", null, "RUNNING").block();

    assertThat(list.truncated()).isFalse();
    assertThat(list.conversations())
        .singleElement()
        .satisfies(c -> {
          assertThat(c.id()).isEqualTo("c-1");
          assertThat(c.protocol()).isEqualTo("PTY");
          assertThat(c.capabilities()).containsExactly("message", "terminal");
        });
  }

  @Test
  void foldsEventsIntoCleanTextWithoutTerminalOutput() {
    found();
    final AgentEvent message =
        new AgentEvent(
            1,
            "c-1",
            NOW,
            AgentEventKind.MESSAGE,
            "i1",
            Map.of("role", "agent", "text", "fine \u001b[31mdone\u001b[0m\ntoken=abcd1234"));
    when(queries.events(eq("c-1"), anyLong(), anyInt(), any(), any()))
        .thenReturn(Mono.just(new AgentConversationQueries.EventPage(List.of(message), 7L)));

    final var view = provider.get("s-1", "c-1", null, null).block();

    assertThat(view.next()).isEqualTo(7L);
    assertThat(view.items())
        .singleElement()
        .satisfies(item -> {
          assertThat(item.kind()).isEqualTo("MESSAGE");
          assertThat(item.role()).isEqualTo("agent");
          assertThat(item.text()).doesNotContain("\u001b", "abcd1234").contains("done");
        });
    verify(queries)
        .events(
            eq("c-1"),
            eq(0L),
            eq(ConversationProvider.MAX_ITEMS),
            org.mockito.ArgumentMatchers.argThat(kinds -> !kinds.contains(AgentEventKind.TERMINAL)),
            any());
  }

  @Test
  void sendsMessageInterruptAndEndAsControlCommands() {
    found();
    when(control.agentMessage("e-1", "n-1", "c-1", "hi", true)).thenReturn(Mono.empty());
    when(control.agentInterrupt("e-1", "n-1", "c-1")).thenReturn(Mono.empty());
    when(control.agentEnd("e-1", "n-1", "c-1", "success", null)).thenReturn(Mono.empty());

    assertThat(provider.message("s-1", "c-1", "hi", true).block().action())
        .isEqualTo("SEND_AGENT_MESSAGE");
    assertThat(provider.interrupt("s-1", "c-1").block().nodeId()).isEqualTo("n-1");
    assertThat(provider.end("s-1", "c-1", "success", " ").block().action())
        .isEqualTo("END_AGENT_CONVERSATION");
  }

  @Test
  void refusalIsToolErrorWithItsReason() {
    found();
    when(control.agentMessage("e-1", "n-1", "c-1", "hi", false))
        .thenReturn(
            Mono.error(
                new AgentSteeringException(
                    AgentSteeringException.Reason.UNSUPPORTED, "lacks message", "message")));

    assertThatThrownBy(() -> provider.message("s-1", "c-1", "hi", false).block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported: lacks message");
  }

  @Test
  void conversationOfAnotherSessionIsNotFound() {
    when(queries.require(eq("c-1"), any()))
        .thenAnswer(call -> {
          final java.util.function.Predicate<String> reaches = call.getArgument(1);
          return reaches.test("s-1")
              ? Mono.just(conversation(Set.of()))
              : Mono.error(new NotFoundException("Conversation not found: c-1"));
        });

    assertThatThrownBy(() -> provider.message("other", "c-1", "hi", false).block())
        .isInstanceOf(NotFoundException.class);
    verify(control, never()).agentMessage(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void readsTheScreenAsCleanedLines() {
    when(queries.screen(eq("c-1"), any()))
        .thenReturn(
            Mono.just(
                new AgentEvent(
                    3,
                    "c-1",
                    NOW,
                    AgentEventKind.TERMINAL,
                    null,
                    Map.of("screen", "line one\n\u0007line two", "cols", 80, "rows", 24))));

    final var screen = provider.screen("s-1", "c-1").block();

    assertThat(screen.cols()).isEqualTo(80);
    assertThat(screen.text().split("\n")).hasSize(2);
    assertThat(screen.text()).doesNotContain("\u0007");
  }

  @Test
  void typesTextThenNamedKeys_andRefusesAnUnknownKey() {
    found();
    when(steering.terminalInput(eq("c-1"), any())).thenReturn(Mono.empty());

    provider.keys("s-1", "c-1", "ls", "enter, ctrl-c").block();

    verify(steering)
        .terminalInput("c-1", "ls\r\u0003".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(() -> provider.keys("s-1", "c-1", null, "f13").block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown key 'f13'");
    assertThatThrownBy(() -> provider.keys("s-1", "c-1", null, null).block())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
