// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Reads conversations for the clients: REST today, MCP and the CLI later. Every read takes a {@code
 * reaches} test for sessions, so a conversation of a session the caller does not reach is as
 * missing as one that does not exist.
 *
 * <p>{@link #follow} is the live view of a run tree: the stored history after a sequence number,
 * then every change, with no gap and no duplicate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
public class AgentConversationQueries {

  /** The most conversations one list returns. */
  public static final int MAX_LIST = 500;

  /** The conversations a list returns when the caller gives no limit. */
  public static final int DEFAULT_LIST = 100;

  /** The most events one page returns. */
  public static final int MAX_EVENTS_PAGE = 500;

  /** The events a page returns when the caller gives no limit. */
  public static final int DEFAULT_EVENTS_PAGE = 100;

  /** Changes that may pile up for a stream while its history is sent. */
  private static final int SEAM_BUFFER = 1024;

  /** Where the conversations are kept. */
  private final AgentConversationStore store;

  /** The live stream of changes. */
  private final AgentConversationService service;

  /** Knows the tree of runs. */
  private final TaskTrackerService tracker;

  /** Tells when a run has ended. */
  private final ControlBusGateway controlBus;

  /**
   * Which conversations a list asks for.
   *
   * @param sessionId only this session, or null
   * @param executionId only this run (and, with {@code tree}, the runs below it), or null
   * @param tree whether the runs below {@code executionId} count
   * @param states the states to keep; empty for all
   * @param reaches whether the caller reaches a session
   */
  public record Scope(
      @Nullable String sessionId,
      @Nullable String executionId,
      boolean tree,
      Set<AgentState> states,
      Predicate<String> reaches) {

    /** Keeps an immutable copy of the states. */
    public Scope {
      states = Set.copyOf(states);
    }
  }

  /**
   * What a stream follows.
   *
   * @param executionId the root run of the stream; it ends when this run does
   * @param tree whether the runs below it count
   * @param conversationId only this conversation, or null
   * @param kinds the kinds to send; empty for all
   * @param terminal false to leave out the terminal output and send screens only
   * @param afterSeq the sequence number the client already has; 0 for a new client
   * @param reaches whether the caller reaches a session
   */
  public record Follow(
      String executionId,
      boolean tree,
      @Nullable String conversationId,
      Set<AgentEventKind> kinds,
      boolean terminal,
      long afterSeq,
      Predicate<String> reaches) {

    /** Keeps an immutable copy of the kinds. */
    public Follow {
      kinds = Set.copyOf(kinds);
    }
  }

  /**
   * A page of events.
   *
   * @param events the events, in the order of their sequence numbers
   * @param next the sequence number to pass as {@code after} for the next page; null on the last
   */
  public record EventPage(List<AgentEvent> events, @Nullable Long next) {

    /** Copies the events. */
    public EventPage {
      events = List.copyOf(events);
    }
  }

  /**
   * Reads a list of states.
   *
   * @param names comma-separated state names, or null
   * @return the states; empty when none are named
   * @throws IllegalArgumentException naming the valid states, when a name is not one
   */
  public static Set<AgentState> states(final @Nullable String names) {
    final Set<AgentState> states = EnumSet.noneOf(AgentState.class);
    if (names != null) {
      for (final String raw : names.split(",", -1)) {
        final String name = raw.strip();
        if (!name.isEmpty()) {
          states.add(state(name));
        }
      }
    }
    return states;
  }

  /**
   * Reads a list of event kinds.
   *
   * @param names comma-separated kind names, or null
   * @return the kinds; empty when none are named
   * @throws IllegalArgumentException naming the valid kinds, when a name is not one
   */
  public static Set<AgentEventKind> kinds(final @Nullable String names) {
    final Set<AgentEventKind> kinds = EnumSet.noneOf(AgentEventKind.class);
    if (names != null) {
      for (final String raw : names.split(",", -1)) {
        final String name = raw.strip();
        if (!name.isEmpty()) {
          kinds.add(kind(name));
        }
      }
    }
    return kinds;
  }

  /**
   * Lists conversations, newest first.
   *
   * @param scope which ones
   * @param limit the most to return; clamped to {@link #MAX_LIST}, {@link #DEFAULT_LIST} when not
   *     positive
   * @return the conversations the caller reaches
   */
  public Flux<AgentConversation> list(final Scope scope, final int limit) {
    final int size = limit < 1 ? DEFAULT_LIST : Math.min(limit, MAX_LIST);
    return Flux.defer(() -> {
      final boolean byRun = scope.executionId() != null;
      final ConversationFilter filter =
          new ConversationFilter(
              scope.sessionId(),
              byRun && !scope.tree() ? scope.executionId() : null,
              byRun && scope.tree() ? rootOf(scope.executionId()) : null,
              scope.states(),
              Integer.MAX_VALUE);
      return store
          .list(filter)
          .filter(conversation -> scope.reaches().test(conversation.sessionId()))
          .filter(conversation -> inRun(conversation, scope.executionId(), scope.tree()))
          .take(size);
    });
  }

  /**
   * Finds a conversation the caller reaches.
   *
   * @param id the conversation id
   * @param reaches whether the caller reaches a session
   * @return the conversation; fails with {@link NotFoundException} when it is unknown or of a
   *     session the caller does not reach
   */
  public Mono<AgentConversation> require(final String id, final Predicate<String> reaches) {
    return store
        .find(id)
        .filter(conversation -> reaches.test(conversation.sessionId()))
        .switchIfEmpty(Mono.error(() -> new NotFoundException("Conversation not found: " + id)));
  }

  /**
   * Reads a page of the events of a conversation.
   *
   * @param id the conversation id
   * @param after the sequence number of the last event the caller has; 0 for the first page
   * @param limit the most events to return; clamped to {@link #MAX_EVENTS_PAGE}, {@link
   *     #DEFAULT_EVENTS_PAGE} when not positive
   * @param kinds the kinds to keep; empty for all
   * @param reaches whether the caller reaches a session
   * @return the page; fails with {@link NotFoundException} as {@link #require} does
   */
  public Mono<EventPage> events(
      final String id,
      final long after,
      final int limit,
      final Set<AgentEventKind> kinds,
      final Predicate<String> reaches) {
    final int size = limit < 1 ? DEFAULT_EVENTS_PAGE : Math.min(limit, MAX_EVENTS_PAGE);
    return require(id, reaches)
        .flatMap(conversation ->
            store
                .events(new EventFilter(id, null, null, null, kinds), Math.max(0, after), size + 1)
                .collectList()
                .map(found ->
                    found.size() > size
                        ? new EventPage(found.subList(0, size), found.get(size - 1).seq())
                        : new EventPage(found, null)));
  }

  /**
   * Reads the latest screen of a terminal.
   *
   * @param id the conversation id
   * @param reaches whether the caller reaches a session
   * @return the {@code TERMINAL} event of the screen; fails with {@link NotFoundException} when the
   *     conversation is not found or has not drawn a screen yet, and with {@link ConflictException}
   *     when it has no terminal
   */
  public Mono<AgentEvent> screen(final String id, final Predicate<String> reaches) {
    return require(id, reaches)
        .flatMap(conversation ->
            conversation.capabilities().contains(AgentCapability.TERMINAL)
                ? store
                    .latestScreen(id)
                    .switchIfEmpty(
                        Mono.error(() ->
                            new NotFoundException(
                                "The terminal of conversation "
                                    + id
                                    + " has not drawn a screen yet")))
                : Mono.error(
                    new ConflictException(
                        "Conversation " + id + " has no terminal (capability terminal)")));
  }

  /**
   * Follows the conversations of a run tree: the stored changes after {@code afterSeq}, then the
   * live ones, each exactly once and in the order of their sequence numbers. The live changes are
   * collected from the first moment, so none that happen while the history is read is lost; one the
   * history already holds is skipped by its number. A client that falls too far behind is ended
   * (the flux completes) and resumes with the last number it saw. The flux also completes once the
   * run has ended and what it wrote is sent.
   *
   * @param follow what to follow
   * @return the changes: conversation records and events
   */
  public Flux<AgentChange> follow(final Follow follow) {
    return Flux.defer(() -> {
      final String root = rootOf(follow.executionId());
      final Predicate<AgentConversation> wanted = wanted(follow, root);
      final AtomicLong high = new AtomicLong(follow.afterSeq());
      final Set<String> followed = java.util.concurrent.ConcurrentHashMap.newKeySet();
      final Sinks.Many<AgentChange> seam =
          Sinks.many().unicast().onBackpressureBuffer(Queues.<AgentChange>get(SEAM_BUFFER).get());
      final Disposable upstream =
          service
              .changes()
              .subscribe(
                  change -> {
                    if (!fits(seam.tryEmitNext(change))) {
                      // a full buffer ends the live part: what is missing is read from the store
                      seam.tryEmitComplete();
                    }
                  },
                  error -> seam.tryEmitComplete(),
                  seam::tryEmitComplete);
      final Flux<AgentChange> live =
          seam.asFlux()
              .filter(change -> accept(change, follow, wanted, followed, high))
              .takeUntilOther(ended(follow.executionId()));
      return Flux.concat(
              history(follow, wanted, followed, high, follow.afterSeq()),
              live,
              Flux.defer(() ->
                  service.flush().thenMany(history(follow, wanted, followed, high, high.get()))))
          .doFinally(_ -> upstream.dispose());
    });
  }

  /**
   * Whether the seam took a change. A full buffer is {@code FAIL_ZERO_SUBSCRIBER} while nobody
   * reads it yet (the history is still being sent) and {@code FAIL_OVERFLOW} afterwards.
   */
  private static boolean fits(final Sinks.EmitResult result) {
    return result != Sinks.EmitResult.FAIL_OVERFLOW
        && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER;
  }

  /** The stored changes after a number: conversation records that changed, and events. */
  private Flux<AgentChange> history(
      final Follow follow,
      final Predicate<AgentConversation> wanted,
      final Set<String> followed,
      final AtomicLong high,
      final long after) {
    final ConversationFilter all =
        new ConversationFilter(null, null, rootOf(follow.executionId()), null, Integer.MAX_VALUE);
    return store
        .list(all)
        .filter(wanted)
        .collectList()
        .flatMapMany(conversations -> {
          conversations.forEach(conversation -> followed.add(conversation.id()));
          return Flux.fromIterable(conversations)
              .concatMap(conversation -> eventsOf(follow, conversation, after))
              .collectList()
              .flatMapMany(events -> {
                final List<AgentChange> changes = new ArrayList<>(events);
                conversations.stream()
                    .filter(conversation -> conversation.seq() > after)
                    .map(AgentChange.ConversationChanged::new)
                    .forEach(changes::add);
                changes.sort(Comparator.comparingLong(AgentChange::seq));
                changes.forEach(change -> high.accumulateAndGet(change.seq(), Math::max));
                return Flux.fromIterable(changes);
              });
        });
  }

  private Flux<AgentChange> eventsOf(
      final Follow follow, final AgentConversation conversation, final long after) {
    return store
        .events(
            new EventFilter(conversation.id(), null, null, null, follow.kinds()),
            after,
            Integer.MAX_VALUE)
        .collectList()
        .flatMapMany(events -> {
          final long screen =
              after == 0 ? latestScreenSeq(events) : 0; // a new client starts at the screen
          return Flux.fromIterable(events)
              .filter(event -> terminalOk(event, follow.terminal(), screen))
              .<AgentChange>map(AgentChange.EventAppended::new);
        });
  }

  /** Whether a live change goes to the client, and remember it when it does. */
  private static boolean accept(
      final AgentChange change,
      final Follow follow,
      final Predicate<AgentConversation> wanted,
      final Set<String> followed,
      final AtomicLong high) {
    if (change.seq() <= high.get()) {
      return false;
    }
    final boolean send =
        switch (change) {
          case AgentChange.ConversationChanged changed -> {
            final boolean mine = wanted.test(changed.conversation());
            if (mine) {
              followed.add(changed.conversation().id());
            }
            yield mine;
          }
          case AgentChange.EventAppended appended ->
              followed.contains(appended.event().conversationId())
                  && (follow.kinds().isEmpty() || follow.kinds().contains(appended.event().kind()))
                  && terminalOk(appended.event(), follow.terminal(), 0);
        };
    if (send) {
      high.accumulateAndGet(change.seq(), Math::max);
    }
    return send;
  }

  /** Leaves out terminal output when the client does not want it, and what a screen replaces. */
  private static boolean terminalOk(
      final AgentEvent event, final boolean terminal, final long screenSeq) {
    if (event.kind() != AgentEventKind.TERMINAL || event.data().containsKey("screen")) {
      return true;
    }
    return terminal && event.seq() > screenSeq;
  }

  private static long latestScreenSeq(final List<AgentEvent> events) {
    return events.stream()
        .filter(event -> event.kind() == AgentEventKind.TERMINAL)
        .filter(event -> event.data().containsKey("screen"))
        .mapToLong(AgentEvent::seq)
        .max()
        .orElse(0);
  }

  private Predicate<AgentConversation> wanted(final Follow follow, final String root) {
    final String only = follow.conversationId();
    return conversation ->
        root.equals(conversation.rootExecutionId())
            && inRun(conversation, follow.executionId(), follow.tree())
            && follow.reaches().test(conversation.sessionId())
            && (only == null || only.equals(conversation.id()));
  }

  private boolean inRun(
      final AgentConversation conversation,
      final @Nullable String executionId,
      final boolean tree) {
    if (executionId == null) {
      return true;
    }
    return conversation.executionId().equals(executionId)
        || (tree && tracker.isWithin(conversation.executionId(), executionId));
  }

  private String rootOf(final String executionId) {
    return tracker.lineage(executionId).rootExecutionId();
  }

  /** Completes when the run has ended. */
  private Mono<Void> ended(final String executionId) {
    return controlBus
        .watchExecution(executionId, true)
        .filter(progress -> progress.status().isTerminal() && progress.endTime() != null)
        .next()
        .then();
  }

  private static AgentState state(final String name) {
    for (final AgentState state : AgentState.values()) {
      if (state.name().equals(name.toUpperCase(Locale.ROOT))) {
        return state;
      }
    }
    throw new IllegalArgumentException(
        "Unknown conversation state: "
            + name
            + "; use one of "
            + java.util.Arrays.toString(AgentState.values()));
  }

  private static AgentEventKind kind(final String name) {
    for (final AgentEventKind kind : AgentEventKind.values()) {
      if (kind.name().equals(name.toUpperCase(Locale.ROOT))) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "Unknown event kind: "
            + name
            + "; use one of "
            + java.util.Arrays.toString(AgentEventKind.values()));
  }
}
