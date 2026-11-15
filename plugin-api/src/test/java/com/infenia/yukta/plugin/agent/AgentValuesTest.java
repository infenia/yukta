// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** The records and small types of the agent conversation contract. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class AgentValuesTest {

  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

  private static AgentConversation conversation(final Set<String> capabilities) {
    return new AgentConversation(
        "c1",
        "e1",
        "e0",
        "s1",
        "w1",
        "n1",
        "m1",
        "claude-code",
        AgentProtocol.JSONL,
        null,
        capabilities,
        AgentState.STARTING,
        null,
        null,
        0,
        0,
        Audience.CURRENT_TIER,
        NOW,
        NOW,
        null,
        0);
  }

  @Test
  void conversationKeepsAnImmutableCopyOfItsCapabilities() {
    final Set<String> source = new java.util.HashSet<>(Set.of(AgentCapability.MESSAGE));
    final AgentConversation conversation = conversation(source);
    source.add(AgentCapability.STEER);

    assertThat(conversation.capabilities()).containsExactly(AgentCapability.MESSAGE);
    assertThat(conversation.isEnded()).isFalse();
  }

  @Test
  void unknownCapabilityIsRefusedWithTheKnownOnes() {
    assertThatThrownBy(() -> conversation(Set.of("fly")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown capability 'fly'")
        .hasMessageContaining("message");
  }

  @Test
  void conversationRejectsBlankIdsAndNegativeCounts() {
    final AgentConversation ok = conversation(Set.of());

    assertThatThrownBy(() -> ok.withNodeId(" ")).hasMessageContaining("nodeId");
    assertThatThrownBy(() -> ok.withTurn(-1)).hasMessageContaining("negative");
    assertThatThrownBy(() -> ok.withQueued(-1)).hasMessageContaining("negative");
    assertThatThrownBy(() -> ok.withOutcome("great")).hasMessageContaining("outcome");
  }

  @Test
  void witherChangesOneFieldAndKeepsTheRest() {
    final AgentConversation ended =
        conversation(Set.of())
            .withOutcome(AgentOutcome.SUCCESS)
            .withEndedAt(NOW)
            .withState(AgentState.ENDED);

    assertThat(ended.isEnded()).isTrue();
    assertThat(ended.outcome()).isEqualTo("success");
    assertThat(ended.harness()).isEqualTo("claude-code");
  }

  @Test
  void eventCopiesItsDataInOrderAndAllowsNullValues() {
    final Map<String, Object> data = new HashMap<>();
    data.put("text", "hi");
    data.put("author", null);
    final AgentEvent event = new AgentEvent(1, "c1", NOW, AgentEventKind.MESSAGE, "m1", data);
    data.put("later", 1);

    assertThat(event.data()).containsOnlyKeys("text", "author");
    assertThatThrownBy(() -> event.data().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void specChecksItsParts() {
    final InputIdentity who = new InputIdentity("s1", "w1", "e1", "n1");

    assertThatCode(() ->
            new AgentConversationSpec(
                who,
                "m1",
                "claude-code",
                AgentProtocol.ACP,
                "model",
                Set.of("ask"),
                Audience.CURRENT_TIER))
        .doesNotThrowAnyException();
    assertThatThrownBy(() ->
            new AgentConversationSpec(
                who, "", "claude-code", AgentProtocol.ACP, null, Set.of(), Audience.CURRENT_TIER))
        .hasMessageContaining("messageId");
    assertThatThrownBy(() ->
            new AgentConversationSpec(
                who, "m1", " ", AgentProtocol.ACP, null, Set.of(), Audience.CURRENT_TIER))
        .hasMessageContaining("harness");
  }

  @Test
  void outcomesAreTheThreeKnownStrings() {
    assertThat(AgentOutcome.ALL).containsExactlyInAnyOrder("success", "failure", "incomplete");
    assertThat(AgentOutcome.require("failure")).isEqualTo("failure");
    assertThatThrownBy(() -> AgentOutcome.require(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AgentOutcome.require("done"))
        .hasMessageContaining("[failure, incomplete, success]");
  }

  @Test
  void theTerminalInputCopiesItsBytesBothWays() {
    final byte[] typed = {1, 2, 3};
    final AgentCommand.TerminalInput input = new AgentCommand.TerminalInput(typed, "arun");
    typed[0] = 9;
    input.data()[1] = 9;

    assertThat(input.data()).containsExactly(1, 2, 3);
    assertThat(input).isEqualTo(new AgentCommand.TerminalInput(new byte[] {1, 2, 3}, "arun"));
    assertThat(input.hashCode())
        .isEqualTo(new AgentCommand.TerminalInput(new byte[] {1, 2, 3}, "arun").hashCode());
    assertThat(input).isNotEqualTo(new AgentCommand.TerminalInput(new byte[] {1}, "arun"));
    assertThat(input).isNotEqualTo("text");
    assertThat(input).isNotEqualTo(new AgentCommand.TerminalInput(new byte[] {1, 2, 3}, "other"));
    assertThat(input.toString()).contains("3 bytes").doesNotContain("[1, 2, 3]");
  }

  @Test
  void commandsCheckTheirParts() {
    assertThatThrownBy(() -> new AgentCommand.Message(null, false, "a"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentCommand.Interrupt(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentCommand.End("done", null, "a"))
        .hasMessageContaining("outcome");
    assertThat(new AgentCommand.End(null, null, "a").outcome()).isNull();
    assertThatThrownBy(() -> new AgentCommand.TerminalResize(0, 10))
        .hasMessageContaining("from 1 to 1000");
    assertThatThrownBy(() -> new AgentCommand.TerminalResize(10, 1001))
        .hasMessageContaining("from 1 to 1000");
    assertThat(new AgentCommand.TerminalResize(120, 40).cols()).isEqualTo(120);
  }

  @Test
  void theHandleWithoutTheGatewayDropsEverythingAndNeverSendsTheCommand() {
    final AgentConversationHandle none = AgentConversationHandle.none();

    none.emit(AgentEventKind.MESSAGE, "m1", Map.of("text", "x"));
    none.state(AgentState.RUNNING, null);
    none.queued(2);
    none.end(AgentOutcome.SUCCESS, null);
    none.end(AgentOutcome.SUCCESS, "again");

    assertThat(none.id()).isEqualTo("none");
    assertThatThrownBy(() -> none.end("bogus", null)).isInstanceOf(IllegalArgumentException.class);
    StepVerifier.create(none.commands())
        .expectSubscription()
        .expectNoEvent(java.time.Duration.ofMillis(20))
        .thenCancel()
        .verify();
    assertThat(List.of(AgentProtocol.values())).hasSize(3);
  }

  @Test
  void endedNeedsAnOutcomeAndAnEndTimeAndNullIdsAreRefused() {
    final AgentConversation live = conversation(Set.of());

    assertThatThrownBy(() -> live.withState(AgentState.ENDED))
        .hasMessageContaining("outcome and an end time");
    assertThatThrownBy(() -> live.withOutcome(AgentOutcome.FAILURE).withState(AgentState.ENDED))
        .hasMessageContaining("outcome and an end time");
    assertThatThrownBy(() -> live.withId(null)).hasMessageContaining("id must not be blank");
  }

  @Test
  void specRefusesNullTextParts() {
    final InputIdentity who = new InputIdentity("s1", "w1", "e1", "n1");

    assertThatThrownBy(() ->
            new AgentConversationSpec(
                who, null, "h", AgentProtocol.PTY, null, Set.of(), Audience.CURRENT_TIER))
        .hasMessageContaining("messageId");
    assertThatThrownBy(() ->
            new AgentConversationSpec(
                who, "m", null, AgentProtocol.PTY, null, Set.of(), Audience.CURRENT_TIER))
        .hasMessageContaining("harness");
  }

  @Test
  void validCommandsAreBuiltAsGiven() {
    final AgentCommand.Message message = new AgentCommand.Message("hi", true, "arun");
    final AgentCommand.Interrupt interrupt = new AgentCommand.Interrupt("arun");
    final AgentCommand.End end = new AgentCommand.End("success", "done", "arun");

    assertThat(message.text()).isEqualTo("hi");
    assertThat(message.interrupt()).isTrue();
    assertThat(message.actor()).isEqualTo("arun");
    assertThat(interrupt.actor()).isEqualTo("arun");
    assertThat(end.outcome()).isEqualTo("success");
    assertThat(end.reason()).isEqualTo("done");
    assertThatThrownBy(() -> new AgentCommand.Message("hi", false, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentCommand.End(null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentCommand.TerminalInput(null, "a"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentCommand.TerminalInput(new byte[0], null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void everyEdgeOfTheTerminalSizeIsChecked() {
    assertThatCode(() -> new AgentCommand.TerminalResize(1, 1)).doesNotThrowAnyException();
    assertThatCode(() -> new AgentCommand.TerminalResize(1000, 1000)).doesNotThrowAnyException();
    assertThatThrownBy(() -> new AgentCommand.TerminalResize(1001, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentCommand.TerminalResize(10, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentCommand.TerminalResize(-1, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
