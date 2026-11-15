// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/**
 * Opens conversations, records what happens in them and announces every change.
 *
 * <p>Every write goes through one {@link SerialWriter}, so the store numbers the changes in the
 * order the live stream shows them. A conversation that has ended accepts no event and no state
 * change; ending twice does nothing. The data of an event is cleaned first ({@link
 * EventSanitizer}): cut to the limits and stripped of secrets.
 */
@Slf4j
@Component
@RegisterReflectionForBinding({AgentConversation.class, AgentEvent.class})
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
public class AgentConversationService implements DisposableBean {

  /** How many changes a subscriber of the live stream may fall behind. */
  public static final int STREAM_BUFFER = 1024;

  /** Where the conversations are kept. */
  private final AgentConversationStore store;

  /** Cuts and cleans event data. */
  private final EventSanitizer sanitizer;

  /** The time of a change. */
  private final Clock clock;

  /** Orders the writes. */
  private final SerialWriter writer = new SerialWriter();

  /** The live stream, before each subscriber's own buffer. */
  private final Sinks.Many<AgentChange> changeSink = Sinks.many().multicast().directBestEffort();

  /** The conversations whose agent runs here and has not ended. */
  private final Map<String, LiveAgentConversation> liveByConversation = new ConcurrentHashMap<>();

  /**
   * Creates the service.
   *
   * @param store where the conversations are kept
   * @param redactor removes secrets from what is stored
   * @param properties the {@code yukta.agents} settings
   * @param json writes structured tool input as text when it is too long
   */
  @Autowired
  public AgentConversationService(
      final AgentConversationStore store,
      final InputRedactor redactor,
      final YuktaProperties properties,
      final ObjectMapper json) {
    this(store, redactor, properties.agents(), json, Clock.systemUTC());
  }

  /**
   * Creates the service with an explicit clock.
   *
   * @param store where the conversations are kept
   * @param redactor removes secrets from what is stored
   * @param settings the {@code yukta.agents} settings
   * @param json writes structured tool input as text when it is too long
   * @param clock the time of a change
   */
  public AgentConversationService(
      final AgentConversationStore store,
      final InputRedactor redactor,
      final YuktaProperties.Agents settings,
      final ObjectMapper json,
      final Clock clock) {
    this.store = store;
    this.sanitizer =
        new EventSanitizer(redactor, json, settings.maxMessageBytes(), settings.maxToolBytes());
    this.clock = clock;
  }

  /**
   * Opens a conversation in state {@code STARTING}.
   *
   * @param spec what the plugin knows about it
   * @param rootExecutionId the top of the run's tree of runs
   * @return the handle the plugin writes through; fails if the conversation cannot be stored
   */
  public Mono<AgentConversationHandle> open(
      final AgentConversationSpec spec, final String rootExecutionId) {
    return writer.submit(() -> create(spec, rootExecutionId));
  }

  /**
   * Finds a conversation whose agent runs on this server and has not ended.
   *
   * @param conversationId the conversation
   * @return its handle, or empty
   */
  public Optional<LiveAgentConversation> live(final String conversationId) {
    return Optional.ofNullable(liveByConversation.get(conversationId));
  }

  /**
   * Returns the changes from now on. Every subscriber has its own buffer of {@link #STREAM_BUFFER};
   * one that falls further behind fails with {@link AgentStreamLaggedException} and reconnects with
   * the last number it saw, reading the rest from the store.
   *
   * @return the live changes
   */
  public Flux<AgentChange> changes() {
    return changeSink
        .asFlux()
        .onBackpressureBuffer(STREAM_BUFFER, dropped -> {}, BufferOverflowStrategy.ERROR)
        .onErrorMap(Exceptions::isOverflow, error -> new AgentStreamLaggedException(STREAM_BUFFER));
  }

  /**
   * Waits until every change made before the call is stored and announced.
   *
   * @return completes then
   */
  public Mono<Void> flush() {
    return writer.submit(Mono::<Void>empty);
  }

  @Override
  public void destroy() {
    writer.dispose();
    changeSink.tryEmitComplete();
  }

  /* default */ void appendEvent(
      final LiveAgentConversation handle,
      final AgentEventKind kind,
      final @Nullable String itemId,
      final Map<String, Object> data) {
    run(() -> doAppend(handle, kind, itemId, data));
  }

  /* default */ void changeState(
      final LiveAgentConversation handle, final AgentState state, final @Nullable String reason) {
    run(() -> doChangeState(handle, state, reason));
  }

  /* default */ void changeQueued(final LiveAgentConversation handle, final int count) {
    run(() -> doChangeQueued(handle, count));
  }

  /* default */ void finish(
      final LiveAgentConversation handle, final String outcome, final @Nullable String reason) {
    run(() -> doFinish(handle, outcome, reason));
  }

  private void run(final java.util.function.Supplier<Mono<Void>> job) {
    // a failed write is logged by the writer; the plugin must not fail for it
    writer.submit(job).onErrorResume(error -> Mono.empty()).subscribe();
  }

  private Mono<AgentConversationHandle> create(
      final AgentConversationSpec spec, final String rootExecutionId) {
    final Instant now = clock.instant();
    final AgentConversation conversation =
        new AgentConversation(
            UUID.randomUUID().toString(),
            spec.who().executionId(),
            rootExecutionId,
            spec.who().sessionId(),
            spec.who().workflowId(),
            spec.who().nodeId(),
            spec.messageId(),
            spec.harness(),
            spec.protocol(),
            spec.model(),
            spec.capabilities(),
            AgentState.STARTING,
            null,
            null,
            0,
            0,
            spec.steerAudience(),
            now,
            now,
            null,
            0);
    return store
        .save(conversation)
        .flatMap(saved -> {
          final LiveAgentConversation handle = new LiveAgentConversation(this, saved);
          liveByConversation.put(saved.id(), handle);
          changeSink.tryEmitNext(new AgentChange.ConversationChanged(saved));
          return storeEvent(
                  saved.id(), AgentEventKind.STATE, null, stateData(AgentState.STARTING, null))
              .thenReturn(handle);
        });
  }

  private Mono<Void> doAppend(
      final LiveAgentConversation handle,
      final AgentEventKind kind,
      final @Nullable String itemId,
      final Map<String, Object> data) {
    if (handle.conversation().isEnded()) {
      return Mono.empty();
    }
    final String id = handle.id();
    final List<Map<String, Object>> parts = sanitizer.sanitize(kind, data);
    return Flux.fromIterable(parts)
        .concatMap(part -> storeEvent(id, kind, itemId, part))
        .then(Mono.defer(() -> kind == AgentEventKind.RESULT ? countTurn(handle) : Mono.empty()));
  }

  private Mono<Void> countTurn(final LiveAgentConversation handle) {
    final AgentConversation conversation = handle.conversation();
    return saveChange(
        handle, conversation.withTurn(conversation.turn() + 1).withUpdatedAt(clock.instant()));
  }

  private Mono<Void> doChangeState(
      final LiveAgentConversation handle, final AgentState state, final @Nullable String reason) {
    final AgentConversation conversation = handle.conversation();
    if (conversation.state() == state) {
      return Mono.empty();
    }
    if (!conversation.state().canMoveTo(state)) {
      log.atWarn()
          .addKeyValue("conversationId", conversation.id())
          .addKeyValue("from", conversation.state())
          .addKeyValue("to", state)
          .log("Ignored a state change that is not allowed");
      return Mono.empty();
    }
    final String cleanReason = sanitizer.text(reason);
    return saveChange(
            handle,
            conversation.withState(state).withReason(cleanReason).withUpdatedAt(clock.instant()))
        .then(
            storeEvent(
                conversation.id(), AgentEventKind.STATE, null, stateData(state, cleanReason)));
  }

  private Mono<Void> doChangeQueued(final LiveAgentConversation handle, final int count) {
    final AgentConversation conversation = handle.conversation();
    if (conversation.isEnded() || conversation.queued() == count) {
      return Mono.empty();
    }
    return saveChange(handle, conversation.withQueued(count).withUpdatedAt(clock.instant()));
  }

  private Mono<Void> doFinish(
      final LiveAgentConversation handle, final String outcome, final @Nullable String reason) {
    final AgentConversation conversation = handle.conversation();
    if (conversation.isEnded()) {
      liveByConversation.remove(conversation.id());
      return Mono.empty();
    }
    final Instant now = clock.instant();
    final String cleanReason = sanitizer.text(reason);
    final Map<String, Object> data = stateData(AgentState.ENDED, cleanReason);
    data.put("outcome", outcome);
    return saveChange(
            handle,
            conversation
                .withOutcome(outcome)
                .withEndedAt(now)
                .withState(AgentState.ENDED)
                .withReason(cleanReason)
                .withUpdatedAt(now))
        .then(storeEvent(conversation.id(), AgentEventKind.STATE, null, data))
        .doFinally(signal -> liveByConversation.remove(conversation.id()));
  }

  private Mono<Void> saveChange(final LiveAgentConversation handle, final AgentConversation next) {
    return store
        .save(next)
        .doOnNext(saved -> {
          handle.update(saved);
          changeSink.tryEmitNext(new AgentChange.ConversationChanged(saved));
        })
        .then();
  }

  private Mono<Void> storeEvent(
      final String conversationId,
      final AgentEventKind kind,
      final @Nullable String itemId,
      final Map<String, Object> data) {
    return store
        .append(new AgentEvent(0, conversationId, clock.instant(), kind, itemId, data))
        .doOnNext(result -> {
          changeSink.tryEmitNext(new AgentChange.EventAppended(result.event()));
          if (result.notice() != null) {
            changeSink.tryEmitNext(new AgentChange.EventAppended(result.notice()));
          }
        })
        .then();
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap") // a local map, never shared
  private static Map<String, Object> stateData(
      final AgentState state, final @Nullable String reason) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("state", state.name());
    if (reason != null) {
      data.put("reason", reason);
    }
    return data;
  }
}
