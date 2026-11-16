// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps conversations in memory; they are gone after a restart.
 *
 * <p>Two bounds apply. When there are more than {@code maxConversations}, ended conversations go
 * first, the one that ended longest ago first; a conversation that has not ended is never evicted,
 * so the store may hold more than the bound while many are live. Per conversation, the oldest
 * events go first once there are more than {@code maxEventsPerConversation}, and one notice says
 * so. Terminal output is not an event for this bound: it lives in a ring of bytes per conversation,
 * and the latest screen is kept apart.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap", "PMD.CouplingBetweenObjects"})
public final class InMemoryAgentConversationStore implements AgentConversationStore {

  /** The text of the notice that says events were dropped. */
  /** Guards every field: every access runs under it. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The conversations by id, oldest first. */
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  /** The change sequence. */
  private final AtomicLong sequence = new AtomicLong();

  /** Identifies this sequence. */
  private final String epochId = UUID.randomUUID().toString();

  /** The time of a notice. */
  private final Clock clock;

  /** Most conversations kept before ended ones are evicted. */
  private final int maxConversations;

  /** Most events kept per conversation. */
  private final int maxEvents;

  /** Most bytes of terminal output kept per conversation. */
  private final long ringBytes;

  /** Most pieces of terminal output kept per conversation, so that empty pieces cannot grow it. */
  private static final int MAX_OUTPUT_PIECES = 10_000;

  /**
   * Creates the store.
   *
   * @param maxConversations most conversations kept before ended ones are evicted; at least 1
   * @param maxEventsPerConversation most events kept per conversation; at least 1
   * @param terminalRingBytes most bytes of terminal output kept per conversation; at least 1
   */
  public InMemoryAgentConversationStore(
      final int maxConversations,
      final int maxEventsPerConversation,
      final long terminalRingBytes) {
    this(maxConversations, maxEventsPerConversation, terminalRingBytes, Clock.systemUTC());
  }

  /**
   * Creates the store with an explicit clock.
   *
   * @param maxConversations most conversations kept before ended ones are evicted; at least 1
   * @param maxEventsPerConversation most events kept per conversation; at least 1
   * @param terminalRingBytes most bytes of terminal output kept per conversation; at least 1
   * @param clock the time of a notice
   */
  public InMemoryAgentConversationStore(
      final int maxConversations,
      final int maxEventsPerConversation,
      final long terminalRingBytes,
      final Clock clock) {
    if (maxConversations < 1 || maxEventsPerConversation < 1 || terminalRingBytes < 1) {
      throw new IllegalArgumentException("every bound of the store must be at least 1");
    }
    this.maxConversations = maxConversations;
    this.maxEvents = maxEventsPerConversation;
    this.ringBytes = terminalRingBytes;
    this.clock = clock;
  }

  @Override
  public String epoch() {
    return epochId;
  }

  @Override
  public Mono<AgentConversation> save(final AgentConversation conversation) {
    return Mono.fromSupplier(() -> locked(() -> saveLocked(conversation)));
  }

  @Override
  public Mono<AppendResult> append(final AgentEvent event) {
    return Mono.fromSupplier(() -> locked(() -> appendLocked(event)));
  }

  @Override
  public Mono<AgentConversation> find(final String id) {
    return Mono.fromSupplier(() -> locked(() -> entries.get(id))).map(entry -> entry.conversation);
  }

  @Override
  public Flux<AgentConversation> list(final ConversationFilter filter) {
    return Flux.defer(() ->
        Flux.fromIterable(
            locked(() ->
                entries.values().stream()
                    .map(entry -> entry.conversation)
                    .filter(filter::matches)
                    .sorted(
                        Comparator.comparing(AgentConversation::startedAt)
                            .thenComparingLong(AgentConversation::seq)
                            .reversed())
                    .limit(filter.limit())
                    .toList())));
  }

  @Override
  public Flux<AgentEvent> events(final EventFilter filter, final long afterSeq, final int limit) {
    return Flux.defer(() -> Flux.fromIterable(locked(() -> eventsLocked(filter, afterSeq, limit))));
  }

  @Override
  public Mono<AgentEvent> latestScreen(final String conversationId) {
    return Mono.fromSupplier(() ->
        locked(
            () -> entries.containsKey(conversationId) ? entries.get(conversationId).screen : null));
  }

  @Override
  public Mono<Long> purge(final Instant before) {
    return Mono.fromSupplier(() ->
        locked(() -> {
          final int sizeBefore = entries.size();
          entries.values().removeIf(entry -> endedBefore(entry.conversation, before));
          return (long) (sizeBefore - entries.size());
        }));
  }

  private AgentConversation saveLocked(final AgentConversation conversation) {
    final AgentConversation stored = conversation.withSeq(sequence.incrementAndGet());
    final Entry entry = entries.get(stored.id());
    if (entry == null) {
      entries.put(stored.id(), new Entry(stored));
    } else {
      entry.conversation = stored;
    }
    evictEnded(stored.id());
    return stored;
  }

  private AppendResult appendLocked(final AgentEvent event) {
    final Entry entry = entries.get(event.conversationId());
    if (entry == null) {
      throw new IllegalArgumentException("Unknown conversation '" + event.conversationId() + "'");
    }
    final AgentEvent stored = withSeq(event, sequence.incrementAndGet());
    if (stored.kind() == AgentEventKind.TERMINAL) {
      if (stored.data().containsKey("screen")) {
        entry.screen = stored;
      } else {
        entry.addOutput(stored, ringBytes);
      }
      return new AppendResult(stored, null);
    }
    entry.events.addLast(stored);
    if (entry.events.size() <= maxEvents) {
      return new AppendResult(stored, null);
    }
    entry.events.removeFirst();
    return new AppendResult(stored, noticeOnFirstDrop(entry));
  }

  private @Nullable AgentEvent noticeOnFirstDrop(final Entry entry) {
    if (entry.notice != null) {
      return null;
    }
    entry.notice =
        new AgentEvent(
            sequence.incrementAndGet(),
            entry.conversation.id(),
            clock.instant(),
            AgentEventKind.NOTICE,
            null,
            Map.of("level", "WARN", "text", DROPPED_NOTICE));
    return entry.notice;
  }

  private List<AgentEvent> eventsLocked(
      final EventFilter filter, final long afterSeq, final int limit) {
    final List<AgentEvent> found = new ArrayList<>();
    for (final Entry entry : entries.values()) {
      if (filter.matches(entry.conversation)) {
        entry.collect(found, afterSeq, filter);
      }
    }
    found.sort(Comparator.comparingLong(AgentEvent::seq));
    return found.stream().limit(Math.max(0, limit)).toList();
  }

  /** Removes ended conversations, the one that ended longest ago first, down to the bound. */
  private void evictEnded(final String keep) {
    final int excess = entries.size() - maxConversations;
    if (excess <= 0) {
      return;
    }
    entries.values().stream()
        .filter(entry -> entry.conversation.isEnded())
        .filter(entry -> !entry.conversation.id().equals(keep))
        .sorted(
            Comparator.comparing(
                entry -> entry.conversation.endedAt(),
                Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(excess)
        .map(entry -> entry.conversation.id())
        .toList()
        .forEach(entries::remove);
  }

  private static boolean endedBefore(final AgentConversation conversation, final Instant before) {
    final Instant endedAt = conversation.endedAt();
    return endedAt != null && endedAt.isBefore(before);
  }

  private <T> T locked(final java.util.function.Supplier<T> action) {
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private static AgentEvent withSeq(final AgentEvent event, final long seq) {
    return new AgentEvent(
        seq, event.conversationId(), event.timestamp(), event.kind(), event.itemId(), event.data());
  }

  /**
   * The bytes a base64 text decodes to, without decoding it. The decoder accepts input without
   * padding, so a last group of two characters is one byte and one of three is two.
   */
  private static long decodedLength(final Object data) {
    if (!(data instanceof final String text) || text.isEmpty()) {
      return 0;
    }
    int padding = 0;
    for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '='; i--) {
      padding++;
    }
    final long groups = text.length() / 4L;
    final int rest = text.length() % 4;
    final long tail = rest == 2 ? 1 : rest == 3 ? 2 : 0;
    return Math.max(0, groups * 3 + tail - padding);
  }

  /** Everything the store keeps of one conversation. */
  private static final class Entry {

    /** The record; replaced on every save. */
    private AgentConversation conversation;

    /** The events that are not terminal output, oldest first. */
    private final Deque<AgentEvent> events = new ArrayDeque<>();

    /** The pieces of terminal output that are still kept, oldest first. */
    private final Deque<AgentEvent> output = new ArrayDeque<>();

    /** The bytes the pieces in {@link #output} hold. */
    private long outputBytes;

    /** The latest settled screen, or null. */
    private @Nullable AgentEvent screen;

    /** The notice about dropped events, kept for good once it exists. */
    private @Nullable AgentEvent notice;

    private Entry(final AgentConversation conversation) {
      this.conversation = conversation;
    }

    /**
     * Adds a piece of terminal output; the oldest pieces go first, but the newest always stays.
     * Pieces with no bytes count nothing, so the piece count is capped as well.
     */
    private void addOutput(final AgentEvent piece, final long ringBytes) {
      output.addLast(piece);
      outputBytes += decodedLength(piece.data().get("data"));
      while ((outputBytes > ringBytes || output.size() > MAX_OUTPUT_PIECES) && output.size() > 1) {
        outputBytes -= decodedLength(output.removeFirst().data().get("data"));
      }
    }

    private void collect(
        final List<AgentEvent> into, final long afterSeq, final EventFilter filter) {
      for (final Deque<AgentEvent> source : List.of(events, output)) {
        source.stream().filter(event -> keeps(event, afterSeq, filter)).forEach(into::add);
      }
      for (final AgentEvent single : java.util.Arrays.asList(screen, notice)) {
        if (single != null && keeps(single, afterSeq, filter)) {
          into.add(single);
        }
      }
    }

    private static boolean keeps(
        final AgentEvent event, final long afterSeq, final EventFilter filter) {
      return event.seq() > afterSeq && filter.matches(event);
    }
  }
}
