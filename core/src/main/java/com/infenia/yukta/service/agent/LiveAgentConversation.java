// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * A conversation whose agent runs on this server. The plugin holds it as an {@link
 * AgentConversationHandle}; {@link AgentConversationService#live} hands it to the code that steers
 * the agent, which calls {@link #send}.
 */
public final class LiveAgentConversation implements AgentConversationHandle {

  /** Most commands that may wait for the plugin to read them. */
  private static final int COMMAND_BUFFER = 256;

  /** Does the writing. */
  private final AgentConversationService service;

  /** The record as last stored; only the writer changes it. */
  private final AtomicReference<AgentConversation> current;

  /** The commands for the plugin; one subscriber, bounded. */
  private final Sinks.Many<AgentCommand> commandSink =
      Sinks.many().unicast().onBackpressureBuffer(Queues.<AgentCommand>get(COMMAND_BUFFER).get());

  /** Serialises senders: a sink accepts one emission at a time. */
  private final ReentrantLock commandLock = new ReentrantLock();

  /** Set by the first {@link #end}; from then on nothing is accepted. */
  private final AtomicBoolean ending = new AtomicBoolean();

  /* default */ LiveAgentConversation(
      final AgentConversationService service, final AgentConversation conversation) {
    this.service = service;
    this.current = new AtomicReference<>(conversation);
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return current.get().id();
  }

  /**
   * Returns the record as last stored.
   *
   * @return the conversation
   */
  public AgentConversation conversation() {
    return current.get();
  }

  @Override
  public void emit(
      final AgentEventKind kind, final @Nullable String itemId, final Map<String, Object> data) {
    if (!ending.get()) {
      // the append runs later on the writer: the caller may reuse its map once emit returns
      service.appendEvent(this, kind, itemId, new LinkedHashMap<>(data));
    }
  }

  @Override
  public void state(final AgentState state, final @Nullable String reason) {
    if (state == AgentState.ENDED) {
      throw new IllegalArgumentException("Use end() to end a conversation");
    }
    if (!ending.get()) {
      service.changeState(this, state, reason);
    }
  }

  @Override
  public void queued(final int count) {
    if (count < 0) {
      throw new IllegalArgumentException("queued must not be negative");
    }
    if (!ending.get()) {
      service.changeQueued(this, count);
    }
  }

  @Override
  public Flux<AgentCommand> commands() {
    return commandSink.asFlux();
  }

  @Override
  public void end(final String outcome, final @Nullable String reason) {
    AgentOutcome.require(outcome);
    if (ending.compareAndSet(false, true)) {
      commandLock.lock();
      try {
        commandSink.tryEmitComplete();
      } finally {
        commandLock.unlock();
      }
      service.finish(this, outcome, reason);
    }
  }

  /**
   * Hands a command to the plugin. The caller has checked who may send it and that the conversation
   * has the capability.
   *
   * @param command the command
   * @return true if the plugin will get it; false if the conversation is ending or too many
   *     commands are waiting
   */
  public boolean send(final AgentCommand command) {
    if (ending.get()) {
      return false;
    }
    commandLock.lock();
    try {
      return commandSink.tryEmitNext(command) == Sinks.EmitResult.OK;
    } finally {
      commandLock.unlock();
    }
  }

  /**
   * Tells whether {@link #end} was called.
   *
   * @return true once the conversation is ending or ended
   */
  public boolean isEnding() {
    return ending.get();
  }

  /* default */ void update(final AgentConversation conversation) {
    current.set(conversation);
  }
}
