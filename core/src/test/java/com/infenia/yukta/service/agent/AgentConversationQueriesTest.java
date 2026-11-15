// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** What a client reads of the conversations of a run tree, and how the live view stays exact. */
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.UseConcurrentHashMap"
})
@org.junit.jupiter.api.Timeout(20)
class AgentConversationQueriesTest {

  private static final Predicate<String> ALL = session -> true;

  private final InMemoryAgentConversationStore store =
      new InMemoryAgentConversationStore(100, 5000, 262_144);
  private final AgentConversationService service =
      new AgentConversationService(
          store,
          new InputRedactor(List.of()),
          settings(1000, 1000, 5000),
          JsonMapper.builder().build(),
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final Sinks.Many<WorkflowProgress> runEvents = Sinks.many().replay().latest();
  private final AgentConversationQueries queries =
      new AgentConversationQueries(store, service, tracker, controlBus);

  /* default */ AgentConversationQueriesTest() {
    when(tracker.lineage(anyString())).thenReturn(new TaskTrackerService.Lineage("root", 0));
    when(tracker.isWithin(anyString(), anyString()))
        .thenAnswer(call -> {
          final String run = call.getArgument(0);
          final String ancestor = call.getArgument(1);
          return run.equals(ancestor) || ("root".equals(ancestor) && run.startsWith("child"));
        });
    when(controlBus.watchExecution(anyString(), eq(true))).thenReturn(runEvents.asFlux());
  }

  @AfterEach
  void tearDown() {
    service.destroy();
  }

  private AgentConversationHandle open(
      final String execution, final String session, final String... capabilities) {
    final AgentConversationSpec spec =
        new AgentConversationSpec(
            new InputIdentity(session, "workflow-1", execution, "node-1"),
            "message-1",
            "claude-code",
            AgentProtocol.JSONL,
            null,
            Set.of(capabilities),
            Audience.CURRENT_TIER);
    return service.open(spec, "root").block();
  }

  private static Map<String, Object> text(final String text) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "agent");
    data.put("text", text);
    data.put("more", false);
    return data;
  }

  private void say(final AgentConversationHandle handle, final String text) {
    handle.emit(AgentEventKind.MESSAGE, "m-" + text, text(text));
    service.flush().block();
  }

  private AgentConversationQueries.Follow follow(final long after) {
    return new AgentConversationQueries.Follow("root", true, null, Set.of(), true, after, ALL);
  }

  private static AgentConversationQueries.Scope scope(
      final String execution, final boolean tree, final Predicate<String> reaches) {
    return new AgentConversationQueries.Scope(null, execution, tree, Set.of(), reaches);
  }

  private void endRun() {
    final WorkflowProgress done = mock(WorkflowProgress.class);
    when(done.status()).thenReturn(TaskStatus.SUCCESS);
    when(done.endTime()).thenReturn(Instant.now());
    runEvents.tryEmitNext(done);
  }

  private static List<Long> seqs(final List<AgentChange> changes) {
    return changes.stream().map(AgentChange::seq).toList();
  }

  // --- reading ---

  @Test
  void listsNewestFirstAndKeepsOnlyWhatTheCallerReaches() {
    open("root", "s1");
    open("root", "s2");
    open("root", "s1");

    final List<AgentConversation> all =
        queries.list(scope("root", false, ALL), 0).collectList().block();
    final List<AgentConversation> mine =
        queries.list(scope("root", false, "s1"::equals), 0).collectList().block();

    assertThat(all)
        .hasSize(3)
        .extracting(AgentConversation::seq)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    assertThat(mine).hasSize(2).allSatisfy(c -> assertThat(c.sessionId()).isEqualTo("s1"));
  }

  @Test
  void childRunCountsWithTreeAndNotWithout() {
    open("root", "s1");
    open("child-1", "s1");

    final Set<String> withTree =
        Set.copyOf(
            queries
                .list(scope("root", true, ALL), 0)
                .map(AgentConversation::executionId)
                .collectList()
                .block());
    final Set<String> withoutTree =
        Set.copyOf(
            queries
                .list(scope("root", false, ALL), 0)
                .map(AgentConversation::executionId)
                .collectList()
                .block());

    assertThat(withTree).containsExactlyInAnyOrder("root", "child-1");
    assertThat(withoutTree).containsExactly("root");
  }

  @Test
  void listsFilterBySessionStateAndLimit() {
    final AgentConversationHandle first = open("root", "s1");
    open("root", "s2");
    first.state(AgentState.RUNNING, null);
    service.flush().block();

    final AgentConversationQueries.Scope running =
        new AgentConversationQueries.Scope("s1", null, true, Set.of(AgentState.RUNNING), ALL);

    assertThat(queries.list(running, 0).collectList().block()).hasSize(1);
    assertThat(
            queries
                .list(new AgentConversationQueries.Scope(null, null, true, Set.of(), ALL), 1)
                .collectList()
                .block())
        .hasSize(1);
    assertThat(
            queries
                .list(new AgentConversationQueries.Scope(null, null, true, Set.of(), ALL), 100_000)
                .collectList()
                .block())
        .hasSize(2);
  }

  @Test
  void conversationOfAnotherSessionIsAsMissingAsAnUnknownOne() {
    final AgentConversationHandle handle = open("root", "s2");

    StepVerifier.create(queries.require(handle.id(), "s1"::equals))
        .expectErrorSatisfies(error -> {
          assertThat(error).isInstanceOf(NotFoundException.class);
          assertThat(error.getMessage()).isEqualTo("Conversation not found: " + handle.id());
        })
        .verify();
    StepVerifier.create(queries.require("nope", ALL)).expectError(NotFoundException.class).verify();
    StepVerifier.create(queries.require(handle.id(), ALL)).expectNextCount(1).verifyComplete();
  }

  @Test
  void eventsComeInPagesWithTheNumberToContinueFrom() {
    final AgentConversationHandle handle = open("root", "s1");
    for (int i = 0; i < 5; i++) {
      say(handle, "t" + i);
    }

    final var first = queries.events(handle.id(), 0, 3, Set.of(), ALL).block();
    final var second =
        queries
            .events(handle.id(), java.util.Objects.requireNonNull(first.next()), 3, Set.of(), ALL)
            .block();

    assertThat(first.events()).hasSize(3);
    assertThat(first.next()).isEqualTo(first.events().getLast().seq());
    assertThat(second.events()).hasSize(3);
    assertThat(second.next()).isNull();
    assertThat(first.events().getLast().seq()).isLessThan(second.events().getFirst().seq());
  }

  @Test
  void eventsCanBeNarrowedByKindAndTheLimitIsClamped() {
    final AgentConversationHandle handle = open("root", "s1");
    say(handle, "hello");
    handle.emit(AgentEventKind.NOTICE, null, Map.of("level", "INFO", "text", "fyi"));
    service.flush().block();

    final var notices =
        queries.events(handle.id(), -5, 0, Set.of(AgentEventKind.NOTICE), ALL).block();

    assertThat(notices.events()).hasSize(1);
    assertThat(notices.next()).isNull();
    assertThat(queries.events(handle.id(), 0, 10_000, Set.of(), ALL).block().events())
        .hasSizeGreaterThan(1);
    StepVerifier.create(queries.events("nope", 0, 10, Set.of(), ALL))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void theScreenOfTheTerminalIsReadAndTheConversationWithoutOneIsTheConflict() {
    final AgentConversationHandle plain = open("root", "s1");
    final AgentConversationHandle pty = open("root", "s1", AgentCapability.TERMINAL);

    StepVerifier.create(queries.screen(plain.id(), ALL))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(queries.screen(pty.id(), ALL))
        .expectErrorSatisfies(error ->
            assertThat(error)
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not drawn"))
        .verify();

    pty.emit(
        AgentEventKind.TERMINAL,
        null,
        Map.of(
            "screen",
            "$ ",
            "cols",
            80,
            "rows",
            24,
            "cursorRow",
            0,
            "cursorCol",
            2,
            "settled",
            true));
    service.flush().block();

    StepVerifier.create(queries.screen(pty.id(), ALL))
        .assertNext(event -> assertThat(event.data()).containsEntry("screen", "$ "))
        .verifyComplete();
  }

  @Test
  void namesOfStatesAndKindsAreReadOrRefusedWithTheValidOnes() {
    assertThat(AgentConversationQueries.states(null)).isEmpty();
    assertThat(AgentConversationQueries.states(" ")).isEmpty();
    assertThat(AgentConversationQueries.states("running, idle,,"))
        .containsExactlyInAnyOrder(AgentState.RUNNING, AgentState.IDLE);
    assertThat(AgentConversationQueries.kinds(null)).isEmpty();
    assertThat(AgentConversationQueries.kinds("tool,Notice"))
        .containsExactlyInAnyOrder(AgentEventKind.TOOL, AgentEventKind.NOTICE);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> AgentConversationQueries.states("sleeping"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sleeping")
        .hasMessageContaining("RUNNING");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> AgentConversationQueries.kinds("gossip"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gossip")
        .hasMessageContaining("MESSAGE");
  }

  // --- following ---

  @Test
  void followSendsTheHistoryThenWhatHappensNext() {
    final AgentConversationHandle handle = open("root", "s1");
    say(handle, "one");
    say(handle, "two");

    final List<AgentChange> seen = new ArrayList<>();
    StepVerifier.create(queries.follow(follow(0)).doOnNext(seen::add))
        .expectNextCount(1 + 1 + 2) // the record, the STARTING state, two messages
        .then(() -> say(handle, "three"))
        .assertNext(change -> assertThat(texts(List.of(change))).containsExactly("three"))
        .then(this::endRun)
        .verifyComplete();

    assertThat(seqs(seen)).isSorted().doesNotHaveDuplicates();
    assertThat(seen.getFirst()).isInstanceOf(AgentChange.ConversationChanged.class);
  }

  private static List<String> texts(final List<AgentChange> changes) {
    return changes.stream()
        .filter(AgentChange.EventAppended.class::isInstance)
        .map(change -> ((AgentChange.EventAppended) change).event())
        .filter(event -> event.kind() == AgentEventKind.MESSAGE)
        .map(event -> String.valueOf(event.data().get("text")))
        .toList();
  }

  @Test
  void clientThatReconnectsGetsExactlyWhatItMissed() {
    final AgentConversationHandle handle = open("root", "s1");
    say(handle, "one");
    say(handle, "two");
    final List<AgentChange> first = new ArrayList<>();
    StepVerifier.create(queries.follow(follow(0)).doOnNext(first::add).take(4))
        .expectNextCount(4)
        .verifyComplete();
    final long lastSeen = first.getLast().seq();

    say(handle, "three");
    say(handle, "four");
    final List<AgentChange> resumed = new ArrayList<>();
    endRun();
    StepVerifier.create(queries.follow(follow(lastSeen)).doOnNext(resumed::add))
        .thenConsumeWhile(c -> true)
        .verifyComplete();

    assertThat(texts(resumed)).containsExactly("three", "four");
    assertThat(seqs(resumed)).allMatch(seq -> seq > lastSeen).doesNotHaveDuplicates();
    assertThat(resumed).noneMatch(AgentChange.ConversationChanged.class::isInstance);
  }

  @Test
  void nothingIsLostOrRepeatedWhileEventsAreWrittenDuringTheHandOverToLive() throws Exception {
    final AgentConversationHandle handle = open("root", "s1");
    final int total = 400;
    final Thread writer =
        new Thread(() -> {
          for (int i = 0; i < total; i++) {
            handle.emit(AgentEventKind.MESSAGE, "m" + i, text("t" + i));
          }
        });
    final List<AgentChange> seen = Collections.synchronizedList(new ArrayList<>());
    final reactor.core.Disposable following = queries.follow(follow(0)).subscribe(seen::add);
    writer.start();
    writer.join();
    service.flush().block();
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> texts(List.copyOf(seen)).size() == total);
    following.dispose();

    final List<String> expected = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      expected.add("t" + i);
    }
    assertThat(texts(List.copyOf(seen))).containsExactlyElementsOf(expected);
    assertThat(seqs(List.copyOf(seen))).isSorted().doesNotHaveDuplicates();
  }

  @Test
  void conversationOpenedAfterTheStreamStartedIsFollowedFromItsFirstEvent() {
    StepVerifier.create(queries.follow(follow(0)))
        .then(() -> {
          final AgentConversationHandle late = open("child-7", "s1");
          say(late, "hello");
        })
        .assertNext(
            change -> assertThat(change).isInstanceOf(AgentChange.ConversationChanged.class))
        .assertNext(change -> assertThat(change).isInstanceOf(AgentChange.EventAppended.class))
        .assertNext(change -> assertThat(texts(List.of(change))).containsExactly("hello"))
        .then(this::endRun)
        .verifyComplete();
  }

  @Test
  void theTreeOptionDecidesWhetherTheChildRunIsFollowed() {
    final AgentConversationHandle child = open("child-1", "s1");
    say(child, "from the child");
    final List<AgentChange> withTree = new ArrayList<>();
    final List<AgentChange> withoutTree = new ArrayList<>();
    endRun();

    queries.follow(follow(0)).doOnNext(withTree::add).blockLast(Duration.ofSeconds(5));
    queries
        .follow(new AgentConversationQueries.Follow("root", false, null, Set.of(), true, 0, ALL))
        .doOnNext(withoutTree::add)
        .blockLast(Duration.ofSeconds(5));

    assertThat(texts(withTree)).containsExactly("from the child");
    assertThat(withoutTree).isEmpty();
  }

  @Test
  void onlyTheNamedConversationTheKindsAndTheReachableSessionsAreSent() {
    final AgentConversationHandle mine = open("root", "s1");
    final AgentConversationHandle other = open("root", "s1");
    final AgentConversationHandle hidden = open("root", "s2");
    say(mine, "mine");
    say(other, "other");
    say(hidden, "hidden");
    mine.emit(AgentEventKind.NOTICE, null, Map.of("level", "INFO", "text", "fyi"));
    service.flush().block();
    endRun();

    final List<AgentChange> one = new ArrayList<>();
    queries
        .follow(
            new AgentConversationQueries.Follow(
                "root", true, mine.id(), Set.of(AgentEventKind.MESSAGE), true, 0, "s1"::equals))
        .doOnNext(one::add)
        .blockLast(Duration.ofSeconds(5));
    final List<AgentChange> reachable = new ArrayList<>();
    queries
        .follow(
            new AgentConversationQueries.Follow(
                "root", true, null, Set.of(AgentEventKind.MESSAGE), true, 0, "s1"::equals))
        .doOnNext(reachable::add)
        .blockLast(Duration.ofSeconds(5));

    assertThat(texts(one)).containsExactly("mine");
    assertThat(texts(reachable)).containsExactlyInAnyOrder("mine", "other");
  }

  @Test
  void newTerminalClientStartsAtTheScreenAndOutputCanBeLeftOut() {
    final AgentConversationHandle pty = open("root", "s1", AgentCapability.TERMINAL);
    final String bytes =
        Base64.getEncoder()
            .encodeToString("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    pty.emit(AgentEventKind.TERMINAL, null, Map.of("data", bytes, "cols", 80, "rows", 24));
    pty.emit(
        AgentEventKind.TERMINAL,
        null,
        Map.of(
            "screen",
            "hello",
            "cols",
            80,
            "rows",
            24,
            "cursorRow",
            0,
            "cursorCol",
            5,
            "settled",
            true));
    pty.emit(AgentEventKind.TERMINAL, null, Map.of("data", bytes, "cols", 80, "rows", 24));
    service.flush().block();
    endRun();

    final List<AgentChange> fresh = new ArrayList<>();
    queries.follow(follow(0)).doOnNext(fresh::add).blockLast(Duration.ofSeconds(5));
    final List<AgentChange> screensOnly = new ArrayList<>();
    queries
        .follow(new AgentConversationQueries.Follow("root", true, null, Set.of(), false, 0, ALL))
        .doOnNext(screensOnly::add)
        .blockLast(Duration.ofSeconds(5));

    assertThat(terminalKinds(fresh)).containsExactly("screen", "output");
    assertThat(terminalKinds(screensOnly)).containsExactly("screen");
  }

  private static List<String> terminalKinds(final List<AgentChange> changes) {
    return changes.stream()
        .filter(AgentChange.EventAppended.class::isInstance)
        .map(change -> ((AgentChange.EventAppended) change).event())
        .filter(event -> event.kind() == AgentEventKind.TERMINAL)
        .map(event -> event.data().containsKey("screen") ? "screen" : "output")
        .toList();
  }

  @Test
  void clientThatFallsBehindIsEndedAndResumesWithWhatItLast() {
    final AgentConversationHandle handle = open("root", "s1");
    final List<AgentChange> before = Collections.synchronizedList(new ArrayList<>());
    final java.util.concurrent.atomic.AtomicReference<String> end =
        new java.util.concurrent.atomic.AtomicReference<>();
    final reactor.core.publisher.BaseSubscriber<AgentChange> client =
        new reactor.core.publisher.BaseSubscriber<>() {
          @Override
          protected void hookOnSubscribe(final org.reactivestreams.Subscription subscription) {
            // no demand until the burst is over: the client is slow
          }

          @Override
          protected void hookOnNext(final AgentChange change) {
            before.add(change);
          }

          @Override
          protected void hookOnComplete() {
            end.set("complete");
          }

          @Override
          protected void hookOnError(final Throwable error) {
            end.set("error " + error);
          }
        };
    final int burst = AgentConversationService.STREAM_BUFFER * 3;
    queries.follow(follow(0)).subscribe(client);
    for (int i = 0; i < burst; i++) {
      handle.emit(AgentEventKind.MESSAGE, "m" + i, text("t" + i));
    }
    service.flush().block();

    client.request(Long.MAX_VALUE);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(end.get())
                .as("how the stream ended; the client got " + before.size() + " changes")
                .isEqualTo("complete"));

    final long lastSeen = before.isEmpty() ? 0 : before.getLast().seq();
    final List<AgentChange> resumed = new ArrayList<>();
    endRun();
    queries.follow(follow(lastSeen)).doOnNext(resumed::add).blockLast(Duration.ofSeconds(10));

    final List<String> all = new ArrayList<>(texts(List.copyOf(before)));
    all.addAll(texts(resumed));
    assertThat(all).hasSize(burst).doesNotHaveDuplicates();
  }

  @Test
  void theStreamEndsWithTheRunAfterSendingWhatWasWrittenLast() {
    final AgentConversationHandle handle = open("root", "s1");

    final List<AgentChange> seen = new ArrayList<>();
    StepVerifier.create(queries.follow(follow(0)).doOnNext(seen::add))
        .expectNextCount(2)
        .then(() -> {
          handle.emit(AgentEventKind.MESSAGE, "m", text("last words"));
          handle.end("success", null);
          endRun();
        })
        .thenConsumeWhile(change -> true)
        .verifyComplete();

    assertThat(texts(seen)).containsExactly("last words");
    assertThat(seen.getLast()).isInstanceOf(AgentChange.EventAppended.class);
    assertThat(seen.stream().filter(AgentChange.ConversationChanged.class::isInstance).count())
        .isGreaterThanOrEqualTo(2);
    assertThat(Flux.fromIterable(seen).map(AgentChange::seq).collectList().block())
        .isSorted()
        .doesNotHaveDuplicates();
  }

  @Test
  void followingAnUnknownRunEndsWithTheRunAndSendsNothing() {
    endRun();

    assertThat(
            queries
                .follow(
                    new AgentConversationQueries.Follow("gone", true, null, Set.of(), true, 0, ALL))
                .collectList()
                .block(Duration.ofSeconds(5)))
        .isEmpty();
  }

  @Test
  void blankNamesInAKindListAreSkipped() {
    assertThat(AgentConversationQueries.kinds("tool,, ,notice"))
        .containsExactlyInAnyOrder(AgentEventKind.TOOL, AgentEventKind.NOTICE);
  }

  @Test
  void liveChangesTheHistoryAlreadyHoldsAreSkipped() {
    final AgentConversationHandle handle = open("root", "s1");
    final Sinks.Empty<Void> gate = Sinks.empty();
    final AgentConversationStore held =
        new AgentConversationStore() {
          @Override
          public String epoch() {
            return store.epoch();
          }

          @Override
          public reactor.core.publisher.Mono<AgentConversation> save(final AgentConversation c) {
            return store.save(c);
          }

          @Override
          public reactor.core.publisher.Mono<AppendResult> append(final AgentEvent e) {
            return store.append(e);
          }

          @Override
          public reactor.core.publisher.Mono<AgentConversation> find(final String id) {
            return store.find(id);
          }

          @Override
          public Flux<AgentConversation> list(final ConversationFilter filter) {
            return gate.asMono().thenMany(Flux.defer(() -> store.list(filter)));
          }

          @Override
          public Flux<AgentEvent> events(
              final EventFilter filter, final long after, final int limit) {
            return store.events(filter, after, limit);
          }

          @Override
          public reactor.core.publisher.Mono<AgentEvent> latestScreen(final String id) {
            return store.latestScreen(id);
          }

          @Override
          public reactor.core.publisher.Mono<Long> purge(final Instant before) {
            return store.purge(before);
          }
        };
    final AgentConversationQueries slow =
        new AgentConversationQueries(held, service, tracker, controlBus);
    final List<AgentChange> seen = Collections.synchronizedList(new ArrayList<>());
    final reactor.core.Disposable following = slow.follow(follow(0)).subscribe(seen::add);

    say(handle, "during the read"); // stored and live, while the history read waits
    gate.tryEmitEmpty();
    say(handle, "after the read");
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> texts(List.copyOf(seen)).size() == 2);
    following.dispose();

    assertThat(texts(List.copyOf(seen))).containsExactly("during the read", "after the read");
    assertThat(seqs(List.copyOf(seen))).isSorted().doesNotHaveDuplicates();
  }

  @Test
  void conversationsOfOtherRootsStrangersAndUnreachableSessionsAreNotFollowed() {
    final AgentConversationHandle mine = open("root", "s1");
    final AgentConversationQueries.Follow reachable =
        new AgentConversationQueries.Follow("root", true, null, Set.of(), true, 0, "s1"::equals);
    final List<AgentChange> seen = Collections.synchronizedList(new ArrayList<>());
    final reactor.core.Disposable following = queries.follow(reachable).subscribe(seen::add);

    final AgentConversationHandle otherSession = open("root", "s2");
    final AgentConversationHandle stranger = open("stranger", "s1");
    final AgentConversationHandle otherRoot =
        service
            .open(
                new AgentConversationSpec(
                    new InputIdentity("s1", "workflow-1", "root", "node-1"),
                    "message-1",
                    "claude-code",
                    AgentProtocol.JSONL,
                    null,
                    Set.of(),
                    Audience.CURRENT_TIER),
                "other-root")
            .block();
    say(otherSession, "hidden session");
    say(stranger, "not below the run");
    say(otherRoot, "another tree");
    say(mine, "mine");
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> texts(List.copyOf(seen)).contains("mine"));
    following.dispose();

    assertThat(texts(List.copyOf(seen))).containsExactly("mine");
  }

  @Test
  void liveEventsAreFilteredByKindAndTerminalOutput() {
    final AgentConversationHandle pty = open("root", "s1", AgentCapability.TERMINAL);
    final AgentConversationQueries.Follow onlyMessages =
        new AgentConversationQueries.Follow(
            "root", true, null, Set.of(AgentEventKind.MESSAGE), true, 0, ALL);
    final AgentConversationQueries.Follow noOutput =
        new AgentConversationQueries.Follow("root", true, null, Set.of(), false, 0, ALL);
    final List<AgentChange> messages = Collections.synchronizedList(new ArrayList<>());
    final List<AgentChange> screens = Collections.synchronizedList(new ArrayList<>());
    final reactor.core.Disposable a = queries.follow(onlyMessages).subscribe(messages::add);
    final reactor.core.Disposable b = queries.follow(noOutput).subscribe(screens::add);

    pty.emit(AgentEventKind.NOTICE, null, Map.of("level", "INFO", "text", "fyi"));
    pty.emit(
        AgentEventKind.TERMINAL,
        null,
        Map.of(
            "data",
            Base64.getEncoder()
                .encodeToString("x".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "cols",
            80,
            "rows",
            24));
    pty.emit(
        AgentEventKind.TERMINAL,
        null,
        Map.of(
            "screen",
            "x",
            "cols",
            80,
            "rows",
            24,
            "cursorRow",
            0,
            "cursorCol",
            1,
            "settled",
            true));
    say(pty, "visible");
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> texts(List.copyOf(messages)).contains("visible"));
    a.dispose();
    b.dispose();

    assertThat(messages)
        .filteredOn(AgentChange.EventAppended.class::isInstance)
        .extracting(change -> ((AgentChange.EventAppended) change).event().kind())
        .containsOnly(AgentEventKind.MESSAGE);
    assertThat(terminalKinds(List.copyOf(screens))).containsExactly("screen");
  }

  @Test
  void theStreamIgnoresRunProgressThatIsNotTheEnd() {
    final AgentConversationHandle handle = open("root", "s1");
    final WorkflowProgress running = mock(WorkflowProgress.class);
    when(running.status()).thenReturn(TaskStatus.RUNNING);
    final WorkflowProgress endedWithoutTime = mock(WorkflowProgress.class);
    when(endedWithoutTime.status()).thenReturn(TaskStatus.SUCCESS);
    when(endedWithoutTime.endTime()).thenReturn(null);

    final List<AgentChange> seen = new ArrayList<>();
    StepVerifier.create(queries.follow(follow(0)).doOnNext(seen::add))
        .expectNextCount(2)
        .then(() -> {
          runEvents.tryEmitNext(running);
          runEvents.tryEmitNext(endedWithoutTime);
          say(handle, "still going");
        })
        .expectNextCount(1)
        .then(this::endRun)
        .verifyComplete();

    assertThat(texts(seen)).containsExactly("still going");
  }
}
