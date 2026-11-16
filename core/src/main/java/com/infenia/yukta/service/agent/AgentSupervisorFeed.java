// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/**
 * Puts every change of a conversation with an agent on the execution event feed, so supervisors see
 * it as {@link ExecutionEvent.AgentConversationChanged}. The event carries the conversation, not
 * its text: the feed is best effort, and a supervisor that needs the transcript reads it by REST or
 * MCP. Every 30 seconds the conversations that are still open are announced again, so a rule about
 * how long an agent has been running sees it although nothing changed.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings({"PMD.AvoidSynchronizedAtMethodLevel", "PMD.NullAssignment"})
public class AgentSupervisorFeed implements SmartLifecycle {

  /** How often the conversations that are still open are announced again. */
  private static final Duration TICK = Duration.ofSeconds(30);

  private final AgentConversationService service;
  private final AgentConversationStore store;
  private final ExecutionEventFeed feed;
  private final Clock clock;
  private Disposable subscription;
  private Disposable ticking;

  /**
   * Creates the bridge.
   *
   * @param service the conversations
   * @param store where they are kept, to announce the open ones again
   * @param feed the feed supervisors read
   */
  @Autowired
  public AgentSupervisorFeed(
      final AgentConversationService service,
      final AgentConversationStore store,
      final ExecutionEventFeed feed) {
    this(service, store, feed, Clock.systemUTC());
  }

  /* default */ AgentSupervisorFeed(
      final AgentConversationService service,
      final AgentConversationStore store,
      final ExecutionEventFeed feed,
      final Clock clock) {
    this.service = service;
    this.store = store;
    this.feed = feed;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    if (subscription != null) {
      return;
    }
    subscription =
        service
            .changes()
            .ofType(AgentChange.ConversationChanged.class)
            // a subscriber that lagged is told and starts again: the feed is best effort anyway
            .retryWhen(
                Retry.indefinitely()
                    .filter(AgentStreamLaggedException.class::isInstance)
                    .doBeforeRetry(_ -> log.atWarn().log("The supervisor feed of agents lagged")))
            .subscribe(
                change -> announce(change.conversation()),
                error -> log.atError().setCause(error).log("The supervisor feed of agents ended"));
    // a rule such as "running for more than ten minutes" needs an event while nothing changes
    ticking =
        Flux.interval(TICK)
            .concatMap(_ ->
                store
                    .list(
                        new ConversationFilter(
                            null,
                            null,
                            null,
                            EnumSet.of(
                                AgentState.STARTING,
                                AgentState.RUNNING,
                                AgentState.WAITING,
                                AgentState.IDLE),
                            Integer.MAX_VALUE))
                    .doOnNext(this::announce)
                    .onErrorResume(error -> {
                      log.atWarn().setCause(error).log("Could not list open agents");
                      return Flux.empty();
                    }))
            .subscribe();
  }

  private void announce(final AgentConversation conversation) {
    feed.publish(new ExecutionEvent.AgentConversationChanged(conversation, clock.instant()));
  }

  @Override
  public synchronized void stop() {
    if (subscription != null) {
      subscription.dispose();
      subscription = null;
    }
    if (ticking != null) {
      ticking.dispose();
      ticking = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return subscription != null && !subscription.isDisposed();
  }
}
