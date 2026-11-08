// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

/**
 * Connects the {@code http} engines to the agent API: for each supervisor, the signals an agent
 * streams, the directives it posts and what it may read. Delivery of signals is best effort: an
 * agent that is not connected, or too slow, misses them and catches up from the snapshot.
 */
@Component
public final class AgentHub {

  /** Signals remembered per supervisor, for an agent that reconnects. */
  public static final int REMEMBERED = 1000;

  /** Directives an agent may post before the supervisor takes them; more are refused. */
  public static final int MAX_WAITING_DIRECTIVES = 256;

  /**
   * A signal with its place in the stream.
   *
   * @param cursor the number of the signal, counting up from 1
   * @param signal the signal
   */
  public record Sequenced(long cursor, Signal signal) {}

  /** One supervisor's meeting point. */
  public static final class Channel {

    private final Sinks.Many<Sequenced> signals = Sinks.many().multicast().directBestEffort();
    private final java.util.ArrayDeque<Sequenced> recent = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicLong sequence =
        new java.util.concurrent.atomic.AtomicLong();
    // multicast without auto-cancel: the host subscribes again after an engine failure
    private final Sinks.Many<Directive> directives =
        Sinks.many().multicast().onBackpressureBuffer(MAX_WAITING_DIRECTIVES, false);
    private final Supplier<EngineReads> reads;

    private Channel(final Supplier<EngineReads> reads) {
      this.reads = reads;
    }

    /**
     * The signals, for an agent's stream.
     *
     * @return the signals sent since it subscribed
     */
    public reactor.core.publisher.Flux<Signal> signals() {
      return signals.asFlux().map(Sequenced::signal);
    }

    /**
     * Where the stream is: the number of the newest signal, to resume after.
     *
     * @return the cursor; 0 before the first signal
     */
    public long cursor() {
      return sequence.get();
    }

    /**
     * The signals after a cursor: those still remembered (the newest {@value #REMEMBERED}), then
     * the live ones. A cursor older than what is remembered misses the gap, so read the executions
     * again.
     *
     * @param after the cursor of the last signal the agent saw
     * @return the signals with their cursors
     */
    public reactor.core.publisher.Flux<Sequenced> signalsAfter(final long after) {
      return reactor.core.publisher.Flux.defer(
          () -> {
            final java.util.List<Sequenced> past;
            synchronized (recent) {
              past = recent.stream().filter(s -> s.cursor() > after).toList();
            }
            final long last = past.isEmpty() ? after : past.get(past.size() - 1).cursor();
            return reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.fromIterable(past),
                signals.asFlux().filter(s -> s.cursor() > last));
          });
    }

    /**
     * Hands a directive from the agent to the supervisor.
     *
     * @param directive the directive
     * @return true if the supervisor took it
     */
    public boolean offer(final Directive directive) {
      return directives.tryEmitNext(directive).isSuccess();
    }

    /**
     * Whether a signal with this id was sent to the agent and is still remembered, so a directive
     * may name it as its cause ({@code event:<id>}, {@code answers:<id>}).
     *
     * @param id the id of an event, review or delegation signal
     * @return true if the agent was sent it
     */
    public boolean sent(final String id) {
      synchronized (recent) {
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
      }
    }

    /**
     * What the agent may read.
     *
     * @return the reads of the supervisor
     */
    public EngineReads reads() {
      return reads.get();
    }

    /* default */ void signal(final Signal signal) {
      synchronized (recent) {
        final Sequenced next = new Sequenced(sequence.incrementAndGet(), signal);
        recent.addLast(next);
        while (recent.size() > REMEMBERED) {
          recent.removeFirst();
        }
        signals.tryEmitNext(next);
      }
    }

    /* default */ reactor.core.publisher.Flux<Directive> directives() {
      return directives.asFlux();
    }

    /* default */ void complete() {
      signals.tryEmitComplete();
      directives.tryEmitComplete();
    }
  }

  private final Map<String, Channel> channels = new ConcurrentHashMap<>();

  private final Sinks.Many<String> credentialChanges = Sinks.many().multicast().directBestEffort();

  /**
   * Says that a supervisor's credential was revoked or replaced, so open agent streams end.
   *
   * @param supervisorId the supervisor
   */
  public synchronized void credentialChanged(final String supervisorId) {
    credentialChanges.tryEmitNext(supervisorId);
  }

  /**
   * The supervisors whose credential was revoked or replaced from now on.
   *
   * @return their ids
   */
  public reactor.core.publisher.Flux<String> credentialChanges() {
    return credentialChanges.asFlux();
  }

  /**
   * Opens the channel of a supervisor, replacing an earlier one.
   *
   * @param supervisorId the supervisor
   * @param reads what its engine may read
   * @return the channel
   */
  /* default */ Channel open(final String supervisorId, final Supplier<EngineReads> reads) {
    final Channel channel = new Channel(reads);
    final Channel earlier = channels.put(supervisorId, channel);
    if (earlier != null) {
      earlier.complete();
    }
    return channel;
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

  /* default */ void close(final String supervisorId, final Channel channel) {
    if (channels.remove(supervisorId, channel)) {
      channel.complete();
    }
  }
}
