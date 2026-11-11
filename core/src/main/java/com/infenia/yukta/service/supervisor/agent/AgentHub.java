// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Connects the {@code http} engines to the agent API: for each supervisor, the signals an agent
 * streams, the directives it posts and what it may read. Delivery of signals is best effort: an
 * agent that is not connected, or too slow, misses them and catches up from the snapshot.
 */
@Component
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class AgentHub {

  /** Signals remembered per supervisor, for an agent that reconnects. */
  public static final int REMEMBERED = 1000;

  /** Directives an agent may post before the supervisor takes them; more are refused. */
  public static final int MAX_WAITING_DIRECTIVES = 256;

  /** Runs an engine reads, the most its supervisor's reads return. */
  private static final int OWNED_RUNS = 200;

  /** Signals a reader may fall behind by before it gets {@code EventsMissed} instead. */
  public static final int LIVE_BUFFER = 1024;

  private final Map<String, Channel> channels = new ConcurrentHashMap<>();

  private final Sinks.Many<String> changes = Sinks.many().multicast().directBestEffort();

  private final ReentrantLock changesLock = new ReentrantLock();

  /**
   * A signal with its place in the stream.
   *
   * @param cursor the number of the signal, counting up from 1
   * @param signal the signal
   * @param at when it was sent
   */
  public record Sequenced(long cursor, Signal signal, java.time.Instant at) {

    /**
     * A signal numbered now.
     *
     * @param cursor its number
     * @param signal the signal
     */
    public Sequenced(final long cursor, final Signal signal) {
      this(cursor, signal, java.time.Instant.now());
    }
  }

  /** One supervisor's meeting point. */
  @SuppressWarnings("PMD.TooManyMethods")
  public static final class Channel {

    private final Sinks.Many<Sequenced> signalSink = Sinks.many().multicast().directBestEffort();
    private final Deque<Sequenced> recent = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong sequence = new AtomicLong();
    private final Set<String> answered = new LinkedHashSet<>();
    // multicast without auto-cancel: the host subscribes again after an engine failure
    private final Sinks.Many<Directive> directiveSink =
        Sinks.many().multicast().onBackpressureBuffer(MAX_WAITING_DIRECTIVES, false);
    // one reads source per attachment of the supervisor: each has its own engine
    private final List<Supplier<EngineReads>> readSources = new CopyOnWriteArrayList<>();

    private Channel() {
      // made by AgentHub.lease
    }

    /**
     * How many engines share this channel: one per attachment of the supervisor.
     *
     * @return the number of open leases
     */
    public int engines() {
      return readSources.size();
    }

    /**
     * The signals, for an agent's stream.
     *
     * @return the signals sent since it subscribed
     */
    public Flux<Signal> signals() {
      return signalSink.asFlux().map(Sequenced::signal);
    }

    /**
     * Where the stream is: the number of the newest signal, to resume after.
     *
     * @return the cursor; 0 before the first signal
     */
    public long cursor() {
      return sequence.get();
    }

    /** A gap of {@code count} signals, reported under the cursor just before the next one. */
    private static Sequenced missed(final long cursor, final long count) {
      final java.time.Instant now = java.time.Instant.now();
      return new Sequenced(
          cursor,
          new Signal.Event(
              "missed-" + cursor,
              String.valueOf(cursor),
              new ExecutionEvent.EventsMissed(count, now)),
          now);
    }

    /**
     * The signals after a cursor: those still remembered (the newest {@value #REMEMBERED}), then
     * the live ones. A cursor older than what is remembered misses the gap, so read the executions
     * again.
     *
     * @param after the cursor of the last signal the agent saw
     * @return the signals with their cursors
     */
    public Flux<Sequenced> signalsAfter(final long after) {
      return Flux.defer(
          () -> {
            // listen first, then read what is remembered: a signal sent in between is in one of
            // the two, and the cursor drops the copy
            final Sinks.Many<Sequenced> live =
                Sinks.many()
                    .unicast()
                    .onBackpressureBuffer(Queues.<Sequenced>get(LIVE_BUFFER).get());
            final reactor.core.Disposable listening =
                signalSink
                    .asFlux()
                    .subscribe(
                        // a reader too far behind drops the overflow; the jump in the cursor of the
                        // next signal it gets says how many
                        live::tryEmitNext, live::tryEmitError, live::tryEmitComplete);
            final List<Sequenced> past;
            final long oldest;
            final long current;
            final long start;
            lock.lock();
            try {
              current = sequence.get();
              // a negative cursor means "from now on"
              start = after < 0 ? current : after;
              past = recent.stream().filter(s -> s.cursor() > start).toList();
              oldest = recent.isEmpty() ? current + 1 : recent.peekFirst().cursor();
            } finally {
              lock.unlock();
            }
            final long last = past.isEmpty() ? start : past.getLast().cursor();
            // signals between the cursor and the oldest one remembered are gone
            final long forgotten = Math.max(0, Math.min(oldest, current + 1) - after - 1);
            final Flux<Sequenced> gap =
                after >= 0 && forgotten > 0 ? Flux.just(missed(after, forgotten)) : Flux.empty();
            final AtomicLong seen = new AtomicLong(last);
            final Flux<Sequenced> fresh =
                live.asFlux()
                    .filter(s -> s.cursor() > seen.get())
                    .concatMapIterable(
                        s -> {
                          final long lost = s.cursor() - seen.getAndSet(s.cursor()) - 1;
                          return lost > 0 ? List.of(missed(s.cursor() - 1, lost), s) : List.of(s);
                        });
            return Flux.concat(gap, Flux.fromIterable(past), fresh)
                .doFinally(_ -> listening.dispose());
          });
    }

    /**
     * Hands a directive from the agent to the supervisor.
     *
     * @param directive the directive
     * @return true if the supervisor took it
     */
    public boolean offer(final Directive directive) {
      return directiveSink.tryEmitNext(directive).isSuccess();
    }

    /**
     * Whether a signal with this id was sent to the agent and is still remembered, so a directive
     * may name it as its cause ({@code event:<id>}, {@code answers:<id>}).
     *
     * @param id the id of an event, review or delegation signal
     * @return true if the agent was sent it
     */
    public boolean sent(final String id) {
      lock.lock();
      try {
        return recent.stream()
            .map(Sequenced::signal)
            .anyMatch(
                signal ->
                    switch (signal) {
                      case Signal.Event event -> id.equals(event.id());
                      case Signal.Review review -> id.equals(review.id());
                      case Signal.Delegated delegated -> id.equals(delegated.id());
                      default -> false;
                    });
      } finally {
        lock.unlock();
      }
    }

    /**
     * Takes the one answer an open review or delegation may get: refused when the id names no
     * review or delegation sent to this agent, when the review's time is up, or when it was
     * answered before.
     *
     * @param use what answers it: the verdict, or a command caused by the request; each once
     * @param id the id of the review or delegation
     * @param now the time now
     * @return true the first time, while the request is open
     */
    public boolean claimAnswer(final String use, final String id, final java.time.Instant now) {
      lock.lock();
      try {
        final boolean open =
            recent.stream()
                .anyMatch(
                    s ->
                        switch (s.signal()) {
                          case Signal.Review review ->
                              id.equals(review.id()) && !now.isAfter(s.at().plus(review.timeout()));
                          case Signal.Delegated delegated -> id.equals(delegated.id());
                          default -> false;
                        });
        if (!open || !answered.add(use + ":" + id)) {
          return false;
        }
        while (answered.size() > REMEMBERED) {
          answered.remove(answered.iterator().next());
        }
        return true;
      } finally {
        lock.unlock();
      }
    }

    /**
     * What the agent may read: what the engine reads, or for a supervisor with several attachments
     * the union over its engines.
     *
     * @return the reads of the supervisor
     */
    public EngineReads reads() {
      final List<Supplier<EngineReads>> sources = List.copyOf(readSources);
      if (sources.size() == 1) {
        return sources.getFirst().get();
      }
      return new MergedReads(sources.stream().map(Supplier::get).toList());
    }

    /* default */ void signal(final Signal signal) {
      lock.lock();
      try {
        final Sequenced next = new Sequenced(sequence.incrementAndGet(), signal);
        recent.addLast(next);
        while (recent.size() > REMEMBERED) {
          recent.removeFirst();
        }
        signalSink.tryEmitNext(next);
      } finally {
        lock.unlock();
      }
    }

    /* default */ Flux<Directive> directives() {
      return directiveSink.asFlux();
    }

    /* default */ void complete() {
      signalSink.tryEmitComplete();
      directiveSink.tryEmitComplete();
    }
  }

  /**
   * Says that a supervisor's credential was revoked or replaced, so open agent streams end.
   *
   * @param supervisorId the supervisor
   */
  public void credentialChanged(final String supervisorId) {
    changesLock.lock();
    try {
      changes.tryEmitNext(supervisorId);
    } finally {
      changesLock.unlock();
    }
  }

  /**
   * The supervisors whose credential was revoked or replaced from now on.
   *
   * @return their ids
   */
  public Flux<String> credentialChanges() {
    return changes.asFlux();
  }

  /**
   * Opens the channel of a supervisor for one of its engines. A supervisor has one engine per
   * attachment, and they all share one channel, so one agent and one credential serve every unit
   * the supervisor owns. The channel ends when the last engine closes its lease.
   *
   * @param supervisorId the supervisor
   * @param reads what this engine may read
   * @return the lease; its {@link Lease#channel()} is the shared channel
   */
  /* default */ Lease lease(final String supervisorId, final Supplier<EngineReads> reads) {
    final Channel[] joined = new Channel[1];
    channels.compute(
        supervisorId,
        (id, existing) -> {
          joined[0] = existing == null ? new Channel() : existing;
          joined[0].readSources.add(reads);
          return joined[0];
        });
    return new Lease(supervisorId, joined[0], reads);
  }

  /**
   * Opens the channel of a supervisor and keeps it for the life of the hub, for a caller that has
   * no engine to close.
   *
   * @param supervisorId the supervisor
   * @param reads what its engine may read
   * @return the channel
   */
  /* default */ Channel open(final String supervisorId, final Supplier<EngineReads> reads) {
    return lease(supervisorId, reads).channel();
  }

  /**
   * The channel of a running supervisor.
   *
   * @param supervisorId the supervisor
   * @return the channel, or empty if the supervisor is not running an {@code http} engine
   */
  public Optional<Channel> channel(final String supervisorId) {
    return Optional.ofNullable(channels.get(supervisorId));
  }

  /** One engine's hold on the channel of its supervisor. */
  /* default */ final class Lease {

    private final String supervisorId;
    private final Channel channel;
    private final Supplier<EngineReads> reads;

    private Lease(
        final String supervisorId, final Channel channel, final Supplier<EngineReads> reads) {
      this.supervisorId = supervisorId;
      this.channel = channel;
      this.reads = reads;
    }

    /* default */ Channel channel() {
      return channel;
    }

    /**
     * Whether a directive of the agent is for this engine. The agent's directives reach the engines
     * of all attachments of a supervisor; the one that reads the run a command names takes it, so
     * the others do not refuse it as not owned. A command no engine reads goes to the first, to be
     * refused once.
     *
     * @param directive what the agent posted
     * @return true if this engine takes it
     */
    // ponytail: two attachments on different nodes of one run both read it, and the first takes
    // it; route by the command's node as well if that turns out to matter
    /* default */ Mono<Boolean> takes(final Directive directive) {
      final List<Supplier<EngineReads>> sources = List.copyOf(channel.readSources);
      if (sources.size() < 2
          || !(directive instanceof Directive.Command command)
          || !sources.contains(reads)) {
        return Mono.just(true);
      }
      final String executionId = command.command().executionId();
      return Flux.fromIterable(sources)
          .filterWhen(
              source ->
                  source
                      .get()
                      .executions(null, OWNED_RUNS)
                      .any(run -> run.executionId().equals(executionId)))
          .next()
          .defaultIfEmpty(sources.getFirst())
          .map(owner -> owner == reads);
    }

    /** Gives the channel up; the last engine to do so ends it. */
    /* default */ void close() {
      channels.computeIfPresent(
          supervisorId,
          (id, current) -> {
            if (current != channel || !channel.readSources.remove(reads)) {
              return current;
            }
            if (channel.readSources.isEmpty()) {
              channel.complete();
              return null;
            }
            return current;
          });
    }
  }
}
