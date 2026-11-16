// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.agent.AgentSteeringException.Reason;
import com.infenia.yukta.service.input.ActorInfo;
import com.infenia.yukta.service.input.ActorResolver;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Who may steer a conversation, and what a command needs of it. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert" // the assertions are in refused()
})
class AgentSteeringTest {

  private final InMemoryAgentConversationStore store =
      new InMemoryAgentConversationStore(50, 5000, 262_144);
  private final YuktaProperties.Agents agents = settings(64, 32, 5000);
  private final AgentConversationService service =
      new AgentConversationService(
          store,
          new InputRedactor(List.of()),
          agents,
          JsonMapper.builder().build(),
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final ActorResolver actors = mock(ActorResolver.class);
  private final YuktaProperties properties = mock(YuktaProperties.class);
  private final List<AgentCommand> received = new ArrayList<>();
  private AgentSteering steering;

  @AfterEach
  void tearDown() {
    service.destroy();
  }

  private AgentSteering newSteering(final YuktaProperties.Agents settings) {
    when(properties.agents()).thenReturn(settings);
    return new AgentSteering(service, actors, properties);
  }

  private String open(final List<String> audience, final String... capabilities) {
    steering = newSteering(agents);
    final AgentConversationHandle handle =
        service
            .open(
                new AgentConversationSpec(
                    new InputIdentity("session-1", "workflow-1", "exec-1", "node-1"),
                    "message-1",
                    "claude-code",
                    AgentProtocol.JSONL,
                    "haiku",
                    Set.of(capabilities),
                    new Audience(audience)),
                "root-1")
            .block();
    handle.commands().subscribe(received::add);
    handle.state(AgentState.RUNNING, null);
    return handle.id();
  }

  private void actingAs(final ActorInfo actor) {
    when(actors.resolve()).thenReturn(Mono.just(actor));
  }

  private static ActorInfo operator(final String name) {
    return ActorInfo.human(name, ApiRole.OPERATOR, Set.of());
  }

  private static ActorInfo supervisor(final String id) {
    return ActorInfo.supervisor(
        id,
        new SupervisorCapabilities(null, false, InputRisk.LOW),
        SupervisorScope.parse("global"));
  }

  private void refused(final Mono<Void> call, final Reason reason) {
    assertThatThrownBy(call::block)
        .isInstanceOfSatisfying(
            AgentSteeringException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  @Test
  void anOperatorOfTheDefaultAudienceSendsMessage() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));

    steering.message(id, "hello", false).block();

    assertThat(received).containsExactly(new AgentCommand.Message("hello", false, "key:ann"));
  }

  @Test
  void anAdminSelectorNeedsAnAdmin() {
    final String id = open(List.of("role:ADMIN"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));
    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
    actingAs(ActorInfo.human("root", ApiRole.ADMIN, Set.of()));

    steering.message(id, "hi", false).block();

    assertThat(received).hasSize(1);
  }

  @Test
  void anAdminMatchesAnOperatorSelectorAndViewerDoesNot() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(ActorInfo.human("root", ApiRole.ADMIN, Set.of()));
    steering.message(id, "hi", false).block();
    actingAs(ActorInfo.human("eve", ApiRole.VIEWER, Set.of()));

    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);

    assertThat(received).hasSize(1);
  }

  @Test
  void whenNamedKeyMatchesItsSelectorOnly() {
    final String id = open(List.of("key:ann"), AgentCapability.MESSAGE);
    actingAs(operator("bob"));
    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
    actingAs(operator("ann"));

    steering.message(id, "hi", false).block();

    assertThat(received).hasSize(1);
  }

  @Test
  void anAgentKeyMatchesOnlySelectorThatNamesIt() {
    final String id = open(List.of("role:OPERATOR", "key:bot"), AgentCapability.MESSAGE);
    actingAs(ActorInfo.agent("other", ApiRole.OPERATOR, Set.of()));
    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
    actingAs(ActorInfo.agent("bot", ApiRole.OPERATOR, Set.of()));

    steering.message(id, "hi", false).block();

    assertThat(received).hasSize(1);
  }

  @Test
  void whenSupervisorNeedsSelectorThatNamesItOrAllSupervisors() {
    final String id = open(List.of("supervisor:guard"), AgentCapability.MESSAGE);
    actingAs(supervisor("other"));
    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
    actingAs(supervisor("guard"));
    steering.message(id, "hi", false).block();
    final String all = open(List.of("supervisor:*"), AgentCapability.MESSAGE);
    actingAs(supervisor("anyone"));

    steering.message(all, "hi", false).block();

    assertThat(received).hasSize(2);
    assertThat(received.getFirst())
        .isEqualTo(new AgentCommand.Message("hi", false, "supervisor:guard"));
  }

  @Test
  void whenTokenCallerAndAnUnidentifiedCallerMaySteerNothing() {
    final String id = open(List.of("role:OPERATOR", "supervisor:*"), AgentCapability.MESSAGE);
    actingAs(ActorInfo.external("tier"));
    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
    actingAs(ActorInfo.unauthenticated());

    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
  }

  @Test
  void whenKeyLimitedToOtherSessionsDoesNotSeeTheConversation() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(ActorInfo.human("ann", ApiRole.OPERATOR, Set.of("session-2")));
    assertThatThrownBy(() -> steering.message(id, "hi", false).block())
        .isInstanceOf(NotFoundException.class);
    actingAs(ActorInfo.human("ann", ApiRole.OPERATOR, Set.of("session-1")));

    steering.message(id, "hi", false).block();

    assertThat(received).hasSize(1);
  }

  @Test
  void anUnknownConversationIsNotFound() {
    open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));

    assertThatThrownBy(() -> steering.message("nope", "hi", false).block())
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void whenCommandNeedsTheCapabilityAndTheErrorNamesIt() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));

    assertThatThrownBy(() -> steering.interrupt(id).block())
        .isInstanceOfSatisfying(
            AgentSteeringException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED);
              assertThat(e.capability()).isEqualTo(AgentCapability.INTERRUPT);
            });
    refused(steering.message(id, "hi", true), Reason.UNSUPPORTED);
    final String quiet = open(List.of("role:OPERATOR"));
    refused(steering.message(quiet, "hi", false), Reason.UNSUPPORTED);
  }

  @Test
  void interruptAndEndAreHandedOverWithTheActor() {
    final String id =
        open(List.of("role:OPERATOR"), AgentCapability.MESSAGE, AgentCapability.INTERRUPT);
    actingAs(ActorInfo.human("anonymous", ApiRole.ADMIN, Set.of()));

    steering.interrupt(id).block();
    steering.message(id, "now", true).block();
    steering.end(id, "failure", "enough").block();

    assertThat(received)
        .containsExactly(
            new AgentCommand.Interrupt("anonymous"),
            new AgentCommand.Message("now", true, "anonymous"),
            new AgentCommand.End("failure", "enough", "anonymous"));
  }

  @Test
  void whenConversationThatEndedIsNotLive() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    service.live(id).orElseThrow().end("success", null);
    actingAs(operator("ann"));

    refused(steering.message(id, "hi", false), Reason.NOT_LIVE);
  }

  @Test
  void whenConversationWhoseAgentReadsNoCommandsIsNotLiveOnceTheQueueIsFull() {
    steering = newSteering(agents);
    final String id =
        java.util.Objects.requireNonNull(
                service
                    .open(
                        new AgentConversationSpec(
                            new InputIdentity("session-1", "workflow-1", "exec-1", "node-1"),
                            "message-1",
                            "claude-code",
                            AgentProtocol.JSONL,
                            null,
                            Set.of(),
                            new Audience(List.of("role:OPERATOR"))),
                        "root-1")
                    .block())
            .id();
    actingAs(operator("ann"));
    for (int i = 0; i < 256; i++) {
      steering.end(id, null, null).block();
    }

    refused(steering.end(id, null, null), Reason.NOT_LIVE);
  }

  @Test
  void whenMessageOverTheLimitIsTooLarge() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));

    refused(steering.message(id, "x".repeat(65), false), Reason.TOO_LARGE);
  }

  @Test
  void whenCallerThatSendsTooManyMessagesIsLimitedPerConversation() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    final String other = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));
    for (int i = 0; i < 30; i++) {
      steering.message(id, "m", false).block();
    }

    refused(steering.message(id, "m", false), Reason.RATE_LIMITED);
    steering.message(other, "m", false).block();
  }

  @Test
  void whenMessageWaitsOnlyWhenTheAgentIsBusyAndCannotSteer() {
    final AgentConversation busy =
        conversation(AgentState.RUNNING, Set.of(AgentCapability.MESSAGE));
    final AgentConversation steers =
        conversation(AgentState.RUNNING, Set.of(AgentCapability.MESSAGE, AgentCapability.STEER));
    final AgentConversation idle = conversation(AgentState.IDLE, Set.of(AgentCapability.MESSAGE));

    assertThat(AgentSteering.willQueue(busy, false)).isTrue();
    assertThat(AgentSteering.willQueue(busy, true)).isFalse();
    assertThat(AgentSteering.willQueue(steers, false)).isFalse();
    assertThat(AgentSteering.willQueue(idle, false)).isFalse();
  }

  @Test
  void selectorForSupervisorsMatchesNoKeyAndViewerIsRefusedEvenWhenNamed() {
    final String id = open(List.of("supervisor:*", "key:eve"), AgentCapability.MESSAGE);
    actingAs(operator("ann"));
    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
    actingAs(ActorInfo.human("eve", ApiRole.VIEWER, Set.of()));

    refused(steering.message(id, "hi", false), Reason.FORBIDDEN);
  }

  @Test
  void eachReasonHasItsHttpStatusAndCode() {
    assertThat(Reason.FORBIDDEN.status()).isEqualTo(403);
    assertThat(Reason.NOT_LIVE.status()).isEqualTo(409);
    assertThat(Reason.UNSUPPORTED.status()).isEqualTo(409);
    assertThat(Reason.TOO_LARGE.status()).isEqualTo(413);
    assertThat(Reason.RATE_LIMITED.status()).isEqualTo(429);
    assertThat(Reason.NOT_LIVE.code()).isEqualTo("not_live");
  }

  private static AgentConversation conversation(
      final AgentState state, final Set<String> capabilities) {
    return AgentTestSupport.conversation("c-1").withState(state).withCapabilities(capabilities);
  }

  @Test
  void theTerminalIsTypedAndResized_andTheVolumeIsAuditedOncePerWindow() {
    final String id = open(List.of("role:OPERATOR"), AgentCapability.TERMINAL);
    final List<AuditRecord> audited = new java.util.concurrent.CopyOnWriteArrayList<>();
    steering.setAuditSink(audited::add);
    steering.auditEvery(java.time.Duration.ofMillis(100));
    actingAs(operator("ann"));

    steering.terminalInput(id, new byte[] {'l', 's'}).block();
    steering.terminalInput(id, new byte[] {'\r'}).block();
    steering.terminalResize(id, 100, 30).block();

    assertThat(received)
        .hasSize(3)
        .element(0)
        .isInstanceOfSatisfying(
            AgentCommand.TerminalInput.class,
            input -> assertThat(input.data()).containsExactly('l', 's'));
    assertThat(received.get(2)).isEqualTo(new AgentCommand.TerminalResize(100, 30));
    org.awaitility.Awaitility.await().until(() -> !audited.isEmpty());
    assertThat(audited).hasSize(1);
    assertThat(audited.getFirst().command()).isEqualTo("terminal input, 3 bytes");
    assertThat(audited.getFirst().actor()).isEqualTo("key:ann");
    assertThat(audited.getFirst().executionId()).isEqualTo("exec-1");
  }

  @Test
  void terminalInputIsBoundedAndNeedsTheTerminalCapability() {
    final String withTerminal = open(List.of("role:OPERATOR"), AgentCapability.TERMINAL);
    actingAs(operator("ann"));
    refused(
        steering.terminalInput(withTerminal, new byte[AgentSteering.MAX_TERMINAL_INPUT_BYTES + 1]),
        Reason.TOO_LARGE);
    final String without = open(List.of("role:OPERATOR"), AgentCapability.MESSAGE);
    refused(steering.terminalInput(without, new byte[] {'x'}), Reason.UNSUPPORTED);
    refused(steering.terminalResize(without, 80, 24), Reason.UNSUPPORTED);
  }

  @Test
  void callerOutsideTheSteerAudienceMayNotType() {
    final String id = open(List.of("key:ann"), AgentCapability.TERMINAL);
    actingAs(operator("bob"));

    refused(steering.terminalInput(id, new byte[] {'x'}), Reason.FORBIDDEN);
    assertThat(received).isEmpty();
  }
}
