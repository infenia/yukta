// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.settings;
import static com.infenia.yukta.service.agent.AgentTestSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** The service opens conversations, records changes in order and refuses what the rules refuse. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseConcurrentHashMap",
  "PMD.ExcessiveImports"
})
class AgentConversationServiceTest {

  private final InMemoryAgentConversationStore store =
      new InMemoryAgentConversationStore(50, 5000, 262_144);
  private final List<Disposable> subscriptions = new ArrayList<>();

  private AgentConversationService service = newService(settings(64, 32, 5000), store);

  private static AgentConversationService newService(
      final com.infenia.yukta.config.YuktaProperties.Agents settings,
      final AgentConversationStore store) {
    return new AgentConversationService(
        store,
        new InputRedactor(List.of()),
        settings,
        JsonMapper.builder().build(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    subscriptions.forEach(Disposable::dispose);
    service.destroy();
  }

  private AgentConversationHandle open() {
    return service.open(spec(AgentCapability.MESSAGE), "root-1").block();
  }

  private AgentConversation stored(final AgentConversationHandle handle) {
    return store.find(handle.id()).block();
  }

  private AgentEvent lastEvent(final AgentConversationHandle handle, final int limit) {
    final List<AgentEvent> all =
        java.util.Objects.requireNonNull(
            store.events(EventFilter.forConversation(handle.id()), 0, limit).collectList().block());
    return all.getLast();
  }

  private List<AgentEvent> events(final AgentConversationHandle handle) {
    service.flush().block();
    return store.events(EventFilter.forConversation(handle.id()), 0, 1000).collectList().block();
  }

  /** A list that contains itself: Jackson cannot write it. */
  private static List<Object> selfReferencing() {
    final List<Object> list = new ArrayList<>();
    list.add(list);
    return list;
  }

  private static Map<String, Object> data(final Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("data needs key and value pairs");
    }
    final Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  @Test
  void openingStartsTheConversationInStateStartingWithItsRootAndTheFirstStateEvent() {
    final AgentConversationHandle handle = open();

    final AgentConversation conversation = stored(handle);
    assertThat(conversation.state()).isEqualTo(AgentState.STARTING);
    assertThat(conversation.rootExecutionId()).isEqualTo("root-1");
    assertThat(conversation.executionId()).isEqualTo("exec-1");
    assertThat(conversation.sessionId()).isEqualTo("session-1");
    assertThat(conversation.nodeId()).isEqualTo("node-1");
    assertThat(conversation.model()).isEqualTo("haiku");
    assertThat(conversation.startedAt()).isEqualTo(NOW);
    assertThat(handle.id()).isEqualTo(conversation.id());
    assertThat(service.live(handle.id())).isPresent();
    assertThat(events(handle))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.kind()).isEqualTo(AgentEventKind.STATE);
          assertThat(event.data()).containsEntry("state", "STARTING");
        });
  }

  @Test
  void stateChangesFollowTheTableAndEachRecordsTheStateEvent() {
    final AgentConversationHandle handle = open();

    handle.state(AgentState.RUNNING, null);
    handle.state(AgentState.WAITING, "approval");
    handle.state(AgentState.RUNNING, null);

    final AgentConversation conversation = stored(handle);
    assertThat(conversation.state()).isEqualTo(AgentState.RUNNING);
    assertThat(conversation.reason()).isNull();
    assertThat(events(handle))
        .extracting(e -> e.data().get("state") + ":" + e.data().get("reason"))
        .containsExactly("STARTING:null", "RUNNING:null", "WAITING:approval", "RUNNING:null");
  }

  @Test
  void moveThatIsNotAllowedAndTheMoveToTheSameStateAreIgnored() {
    final AgentConversationHandle handle = open();
    final long seq = stored(handle).seq();

    handle.state(AgentState.IDLE, "not from starting");
    handle.state(AgentState.STARTING, "same");
    service.flush().block();

    assertThat(stored(handle).state()).isEqualTo(AgentState.STARTING);
    assertThat(stored(handle).seq()).isEqualTo(seq);
    assertThat(events(handle)).hasSize(1);
  }

  @Test
  void endedIsReachedOnlyThroughEnd() {
    final AgentConversationHandle handle = open();

    assertThatThrownBy(() -> handle.state(AgentState.ENDED, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("end()");
  }

  @Test
  void turnIsCountedWhenTheResultIsRecorded() {
    final AgentConversationHandle handle = open();
    handle.state(AgentState.RUNNING, null);

    handle.emit(AgentEventKind.RESULT, null, data("status", "success", "text", "done", "turn", 1));
    handle.emit(AgentEventKind.RESULT, null, data("status", "success", "text", "again", "turn", 2));
    service.flush().block();

    assertThat(stored(handle).turn()).isEqualTo(2);
  }

  @Test
  void theQueuedCountIsRecordedAndAnUnchangedCountIsNotTheChange() {
    final AgentConversationHandle handle = open();

    handle.queued(2);
    service.flush().block();
    final long seq = stored(handle).seq();
    handle.queued(2);
    service.flush().block();

    assertThat(stored(handle).queued()).isEqualTo(2);
    assertThat(stored(handle).seq()).isEqualTo(seq);
    assertThatThrownBy(() -> handle.queued(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void endingRecordsTheOutcomeAndIsIdempotent() {
    final AgentConversationHandle handle = open();
    handle.state(AgentState.RUNNING, null);

    handle.end("success", "done");
    service.flush().block();
    final AgentConversation first = stored(handle);
    handle.end("failure", "second call");
    service.flush().block();

    assertThat(first.state()).isEqualTo(AgentState.ENDED);
    assertThat(first.outcome()).isEqualTo("success");
    assertThat(first.reason()).isEqualTo("done");
    assertThat(first.endedAt()).isEqualTo(NOW);
    assertThat(stored(handle)).isEqualTo(first);
    assertThat(service.live(handle.id())).isEmpty();
    assertThat(events(handle).getLast().data())
        .containsEntry("state", "ENDED")
        .containsEntry("outcome", "success")
        .containsEntry("reason", "done");
    assertThatThrownBy(() -> open().end("great", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void endedConversationAcceptsNoEventNoStateChangeAndNoQueuedCount() {
    final AgentConversationHandle handle = open();
    handle.state(AgentState.RUNNING, null);
    handle.end("incomplete", null);
    service.flush().block();
    final int count = events(handle).size();
    final long seq = stored(handle).seq();

    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "late"));
    handle.state(AgentState.RUNNING, null);
    handle.queued(5);
    service.flush().block();

    assertThat(events(handle)).hasSize(count);
    assertThat(stored(handle).seq()).isEqualTo(seq);
    assertThat(stored(handle).state()).isEqualTo(AgentState.ENDED);
  }

  @Test
  void eventsRecordedBeforeEndAreKeptAndAfterEndAreNot() {
    final AgentConversationHandle handle = open();

    handle.emit(AgentEventKind.MESSAGE, "m1", data("role", "agent", "text", "before"));
    handle.end("success", null);
    handle.emit(AgentEventKind.MESSAGE, "m2", data("role", "agent", "text", "after"));

    assertThat(events(handle))
        .filteredOn(e -> e.kind() == AgentEventKind.MESSAGE)
        .extracting(e -> e.data().get("text"))
        .containsExactly("before");
  }

  @Test
  void commandsCompleteOnEndAndBufferUntilTheFirstSubscriber() {
    final AgentConversationHandle handle = open();
    final LiveAgentConversation live = service.live(handle.id()).orElseThrow();
    final AgentCommand.Message message = new AgentCommand.Message("hello", false, "arun");

    assertThat(live.send(message)).isTrue();
    final Flux<AgentCommand> commands = handle.commands();
    StepVerifier.create(commands)
        .expectNext(message)
        .then(() -> handle.end("success", null))
        .verifyComplete();
    assertThat(live.isEnding()).isTrue();
    assertThat(live.send(message)).isFalse();
    assertThat(live.conversation().id()).isEqualTo(handle.id());
  }

  @Test
  void senderIsToldWhenTooManyCommandsWait() {
    final AgentConversationHandle handle = open();
    final LiveAgentConversation live = service.live(handle.id()).orElseThrow();

    final List<Boolean> accepted =
        java.util.stream.IntStream.range(0, 300)
            .mapToObj(i -> live.send(new AgentCommand.Interrupt("arun")))
            .toList();

    assertThat(accepted.stream().filter(Boolean::booleanValue)).hasSize(256);
    assertThat(accepted.getLast()).isFalse();
  }

  @Test
  void oversizedTextIsCutAtTheCharacterBoundaryAndFlagged() {
    final AgentConversationHandle handle = open();

    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "é".repeat(100)));
    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "short"));

    final List<AgentEvent> messages =
        events(handle).stream().filter(e -> e.kind() == AgentEventKind.MESSAGE).toList();
    assertThat((String) messages.get(0).data().get("text")).isEqualTo("é".repeat(32));
    assertThat(messages.get(0).data()).containsEntry("truncated", true);
    assertThat(messages.get(1).data()).doesNotContainKey("truncated");
  }

  @Test
  void toolInputAndOutputAreTruncatedWithTheFlagAndSmallOnesStayAsTheyAre() {
    final AgentConversationHandle handle = open();
    final Map<String, Object> small = data("path", "a.txt");
    final Map<String, Object> big = data("content", "x".repeat(500));

    handle.emit(
        AgentEventKind.TOOL, "t1", data("title", "Read", "status", "running", "input", small));
    handle.emit(
        AgentEventKind.TOOL,
        "t2",
        data("title", "Write", "status", "completed", "input", big, "output", "y".repeat(500)));

    final List<AgentEvent> tools =
        events(handle).stream().filter(e -> e.kind() == AgentEventKind.TOOL).toList();
    assertThat(tools.get(0).data()).containsEntry("input", small).doesNotContainKey("truncated");
    assertThat((String) tools.get(1).data().get("input")).hasSize(32).startsWith("{\"content\"");
    assertThat((String) tools.get(1).data().get("output")).isEqualTo("y".repeat(32));
    assertThat(tools.get(1).data()).containsEntry("truncated", true);
  }

  @Test
  void toolInputThatIsNotTheMapOrTheStringBecomesText() {
    final AgentConversationHandle handle = open();

    handle.emit(
        AgentEventKind.TOOL,
        "t",
        data("title", "Run", "status", "running", "input", List.of("a", "b")));

    assertThat(
            events(handle).stream()
                .filter(e -> e.kind() == AgentEventKind.TOOL)
                .findFirst()
                .orElseThrow()
                .data())
        .containsEntry("input", "[\"a\",\"b\"]");
  }

  @Test
  void secretsAreRemovedFromTextTitlesToolDataAndPlans() {
    final AgentConversationHandle handle = open();
    final String secret = "${env.TOP_SECRET}";

    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "use " + secret));
    handle.emit(
        AgentEventKind.TOOL,
        "t",
        data("title", "run " + secret, "status", "running", "input", data("cmd", secret)));
    handle.emit(
        AgentEventKind.PLAN,
        "plan",
        data("entries", List.of(data("text", "call " + secret, "status", "pending"), "plain")));
    handle.state(AgentState.RUNNING, "because " + secret);

    assertThat(events(handle).toString()).doesNotContain("TOP_SECRET").contains("[REDACTED]");
    assertThat(stored(handle).reason()).doesNotContain("TOP_SECRET");
  }

  @Test
  void planThatIsNotTheListBecomesEmpty() {
    final AgentConversationHandle handle = open();

    handle.emit(AgentEventKind.PLAN, "plan", data("entries", "oops"));

    assertThat(
            events(handle).stream()
                .filter(e -> e.kind() == AgentEventKind.PLAN)
                .findFirst()
                .orElseThrow()
                .data())
        .containsEntry("entries", List.of());
  }

  @Test
  void longTerminalChunkBecomesSeveralAndBadBase64IsDropped() {
    final AgentConversationHandle handle = open();
    final byte[] bytes = new byte[40 * 1024];
    final String encoded = Base64.getEncoder().encodeToString(bytes);

    handle.emit(AgentEventKind.TERMINAL, null, data("data", encoded, "cols", 80, "rows", 24));
    handle.emit(
        AgentEventKind.TERMINAL, null, data("data", "%%%not base64", "cols", 80, "rows", 24));
    handle.emit(AgentEventKind.TERMINAL, null, data("cols", 80, "rows", 24));
    handle.emit(AgentEventKind.TERMINAL, null, data("screen", "hello", "cols", 80, "rows", 24));

    final List<AgentEvent> terminal =
        events(handle).stream().filter(e -> e.kind() == AgentEventKind.TERMINAL).toList();
    assertThat(terminal).hasSize(4);
    assertThat(terminal.subList(0, 3))
        .extracting(e -> Base64.getDecoder().decode((String) e.data().get("data")).length)
        .containsExactly(16 * 1024, 16 * 1024, 8 * 1024);
    assertThat(terminal.get(3).data()).containsEntry("screen", "hello");
  }

  @Test
  void everyChangeOnTheLiveStreamHasTheStrictlyIncreasingNumber() {
    final List<AgentChange> seen = Collections.synchronizedList(new ArrayList<>());
    subscriptions.add(service.changes().subscribe(seen::add));

    final AgentConversationHandle handle = open();
    handle.state(AgentState.RUNNING, null);
    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "hi"));
    handle.queued(1);
    handle.emit(AgentEventKind.RESULT, null, data("status", "success", "turn", 1));
    handle.end("success", null);
    service.flush().block();

    assertThat(seen).extracting(AgentChange::seq).isSorted().doesNotHaveDuplicates();
    assertThat(seen).hasAtLeastOneElementOfType(AgentChange.ConversationChanged.class);
    assertThat(seen).hasAtLeastOneElementOfType(AgentChange.EventAppended.class);
    assertThat(seen.getLast()).isInstanceOf(AgentChange.EventAppended.class);
  }

  @Test
  void subscriberThatFallsFarBehindFailsAndTheServiceCarriesOn() {
    final AgentConversationHandle handle = open();
    final AtomicBoolean lagged = new AtomicBoolean();

    StepVerifier.create(service.changes(), 0)
        .then(() -> {
          for (int i = 0; i < AgentConversationService.STREAM_BUFFER + 10; i++) {
            handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "x"));
          }
        })
        .thenRequest(Long.MAX_VALUE)
        .expectNextCount(AgentConversationService.STREAM_BUFFER)
        .expectErrorSatisfies(error -> lagged.set(error instanceof AgentStreamLaggedException))
        .verify(Duration.ofSeconds(5));

    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "after"));
    service.flush().block();
    assertThat(lagged).isTrue();
    assertThat(lastEvent(handle, 5000).data()).containsEntry("text", "after");
  }

  @Test
  void subscriberThatWritesBackFromTheStreamDoesNotDeadlock() {
    final AgentConversationHandle handle = open();
    final AtomicBoolean written = new AtomicBoolean();
    subscriptions.add(
        service
            .changes()
            .subscribe(change -> {
              if (change instanceof AgentChange.ConversationChanged
                  && written.compareAndSet(false, true)) {
                handle.emit(AgentEventKind.NOTICE, null, data("level", "INFO", "text", "reaction"));
              }
            }));

    handle.state(AgentState.RUNNING, null);

    assertThat(events(handle))
        .anySatisfy(e -> assertThat(e.data()).containsEntry("text", "reaction"));
  }

  @Test
  void failedWriteIsLoggedAndTheNextOneStillRuns() {
    final FlakyStore flaky = new FlakyStore(store);
    service = newService(settings(64, 32, 5000), flaky);
    final AgentConversationHandle handle = open();

    flaky.failNextAppend.set(true);
    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "lost"));
    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "kept"));

    assertThat(events(handle))
        .filteredOn(e -> e.kind() == AgentEventKind.MESSAGE)
        .extracting(e -> e.data().get("text"))
        .containsExactly("kept");
  }

  @Test
  void openingFailsWhenTheConversationCannotBeStored() {
    final FlakyStore flaky = new FlakyStore(store);
    service = newService(settings(64, 32, 5000), flaky);
    flaky.failNextSave.set(true);

    StepVerifier.create(service.open(spec(), "root-1")).expectErrorMessage("disk full").verify();
    assertThat(store.list(ConversationFilter.all()).collectList().block()).isEmpty();
  }

  @Test
  void afterShutdownNothingNewIsAccepted() {
    service.destroy();

    StepVerifier.create(service.open(spec(), "root-1"))
        .expectErrorMessage("The conversation writer is stopped")
        .verify();
  }

  @Test
  void theServiceBuiltFromPropertiesUsesTheirLimits() {
    final AgentConversationService fromProperties =
        new AgentConversationService(
            store,
            new InputRedactor(List.of()),
            com.infenia.yukta.testsupport.YuktaSettings.bind(
                Map.of("yukta.agents.max-message-bytes", "8")),
            JsonMapper.builder().build());

    final AgentConversationHandle handle =
        java.util.Objects.requireNonNull(fromProperties.open(spec(), "root-1").block());
    handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "0123456789"));
    fromProperties.flush().block();

    assertThat(lastEvent(handle, 10).data())
        .containsEntry("text", "01234567")
        .containsEntry("truncated", true);
    fromProperties.destroy();
  }

  @Test
  void planKeepsOtherKeysAndNonTextValues() {
    final AgentConversationHandle handle = open();

    handle.emit(
        AgentEventKind.PLAN,
        "plan",
        data("entries", List.of(data("text", "step", "priority", 2)), "version", 3));

    final Map<String, Object> plan =
        events(handle).stream()
            .filter(e -> e.kind() == AgentEventKind.PLAN)
            .findFirst()
            .orElseThrow()
            .data();
    assertThat(plan).containsEntry("version", 3);
    assertThat((List<?>) plan.get("entries"))
        .singleElement()
        .isEqualTo(data("text", "step", "priority", 2));
  }

  @Test
  void smallTerminalChunkIsStoredAsIs() {
    final AgentConversationHandle handle = open();
    final String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});

    handle.emit(AgentEventKind.TERMINAL, null, data("data", encoded, "cols", 80, "rows", 24));

    assertThat(
            events(handle).stream()
                .filter(e -> e.kind() == AgentEventKind.TERMINAL)
                .findFirst()
                .orElseThrow()
                .data())
        .containsEntry("data", encoded);
  }

  @Test
  void toolInputThatCannotBeWrittenAsJsonBecomesItsOwnText() {
    final AgentConversationHandle handle = open();

    handle.emit(
        AgentEventKind.TOOL,
        "t",
        data("title", "Run", "status", "running", "input", selfReferencing()));

    assertThat(
            (String)
                events(handle).stream()
                    .filter(e -> e.kind() == AgentEventKind.TOOL)
                    .findFirst()
                    .orElseThrow()
                    .data()
                    .get("input"))
        .startsWith("[(this Collection)]");
  }

  @Test
  void theNoticeAboutDroppedEventsReachesTheLiveStream() {
    final InMemoryAgentConversationStore small = new InMemoryAgentConversationStore(10, 2, 1000);
    service = newService(settings(64, 32, 2), small);
    final List<AgentChange> seen = Collections.synchronizedList(new ArrayList<>());
    subscriptions.add(service.changes().subscribe(seen::add));
    final AgentConversationHandle handle = open();

    for (int i = 0; i < 4; i++) {
      handle.emit(AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "x"));
    }
    service.flush().block();

    assertThat(seen)
        .filteredOn(AgentChange.EventAppended.class::isInstance)
        .map(change -> ((AgentChange.EventAppended) change).event().kind())
        .filteredOn(kind -> kind == AgentEventKind.NOTICE)
        .hasSize(1);
  }

  @Test
  void writesThatArriveAfterTheEndAreRefusedByTheServiceItself() {
    final AgentConversationHandle handle = open();
    final LiveAgentConversation live = service.live(handle.id()).orElseThrow();
    handle.end("success", null);
    service.flush().block();
    final long seq = stored(handle).seq();
    final int count = events(handle).size();

    service.appendEvent(live, AgentEventKind.MESSAGE, "m", data("role", "agent", "text", "late"));
    service.changeQueued(live, 3);
    service.changeState(live, AgentState.RUNNING, null);
    service.finish(live, "failure", "twice");
    service.flush().block();

    assertThat(stored(handle).seq()).isEqualTo(seq);
    assertThat(stored(handle).outcome()).isEqualTo("success");
    assertThat(events(handle)).hasSize(count);
    assertThat(service.live(handle.id())).isEmpty();
  }

  /** A store that fails on demand. */
  private static final class FlakyStore implements AgentConversationStore {

    private final AgentConversationStore delegate;
    private final AtomicBoolean failNextAppend = new AtomicBoolean();
    private final AtomicBoolean failNextSave = new AtomicBoolean();

    private FlakyStore(final AgentConversationStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public String epoch() {
      return delegate.epoch();
    }

    @Override
    public Mono<AgentConversation> save(final AgentConversation conversation) {
      return failNextSave.getAndSet(false)
          ? Mono.error(new IllegalStateException("disk full"))
          : delegate.save(conversation);
    }

    @Override
    public Mono<AppendResult> append(final AgentEvent event) {
      return failNextAppend.getAndSet(false)
          ? Mono.error(new IllegalStateException("disk full"))
          : delegate.append(event);
    }

    @Override
    public Mono<AgentConversation> find(final String id) {
      return delegate.find(id);
    }

    @Override
    public Flux<AgentConversation> list(final ConversationFilter filter) {
      return delegate.list(filter);
    }

    @Override
    public Flux<AgentEvent> events(final EventFilter filter, final long afterSeq, final int limit) {
      return delegate.events(filter, afterSeq, limit);
    }

    @Override
    public Mono<AgentEvent> latestScreen(final String conversationId) {
      return delegate.latestScreen(conversationId);
    }

    @Override
    public Mono<Long> purge(final java.time.Instant before) {
      return delegate.purge(before);
    }
  }
}
