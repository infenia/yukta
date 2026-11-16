// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where conversations and their events are kept. The store owns the change sequence: every {@link
 * #save} and {@link #append} gets the next number, so the numbers are strictly increasing over
 * conversations and events together. {@link AgentConversationService} writes one change at a time,
 * so the order of the numbers is the order of the live stream.
 */
public interface AgentConversationStore {

  /** The text of the notice that tells a reader the oldest events of a conversation are gone. */
  String DROPPED_NOTICE =
      "The oldest events of this conversation were dropped to stay within"
          + " yukta.agents.max-events-per-conversation; its history is incomplete.";

  /**
   * Returns the identity of this store's sequence. It changes when the sequence restarts (a new
   * in-memory store after a restart), so a client holding a number of another epoch knows it must
   * start over.
   *
   * @return the epoch
   */
  String epoch();

  /**
   * Creates or replaces a conversation.
   *
   * @param conversation the record; its {@code seq} is ignored
   * @return the record as stored, with the next sequence number
   */
  Mono<AgentConversation> save(AgentConversation conversation);

  /**
   * Appends an event to its conversation. When the conversation is over its event limit the store
   * drops the oldest events and, the first time, adds a notice that says so.
   *
   * @param event the event; its {@code seq} is ignored
   * @return the stored event, and the notice if this append caused one; fails if the conversation
   *     is unknown
   */
  Mono<AppendResult> append(AgentEvent event);

  /**
   * Finds a conversation.
   *
   * @param id the conversation id
   * @return the conversation, or empty
   */
  Mono<AgentConversation> find(String id);

  /**
   * Lists conversations, newest first.
   *
   * @param filter which ones
   * @return the matching conversations, at most {@code filter.limit()}
   */
  Flux<AgentConversation> list(ConversationFilter filter);

  /**
   * Reads events in the order of their sequence numbers: the events of every conversation the
   * filter selects, the pieces of terminal output that are still kept, and the latest screen.
   *
   * @param filter which conversations and kinds
   * @param afterSeq the number of the last event the caller has; 0 for all
   * @param limit the most events to return
   * @return the events with a number above {@code afterSeq}
   */
  Flux<AgentEvent> events(EventFilter filter, long afterSeq, int limit);

  /**
   * Returns the latest settled screen of a terminal.
   *
   * @param conversationId the conversation
   * @return the {@code TERMINAL} event that carries it, or empty
   */
  Mono<AgentEvent> latestScreen(String conversationId);

  /**
   * Removes ended conversations, with their events, that ended before a time.
   *
   * @param before the cut-off
   * @return how many conversations were removed
   */
  Mono<Long> purge(Instant before);

  /**
   * The result of {@link #append}.
   *
   * @param event the event as stored
   * @param notice the notice about dropped events, set only on the append that caused it
   */
  record AppendResult(AgentEvent event, @Nullable AgentEvent notice) {}
}
