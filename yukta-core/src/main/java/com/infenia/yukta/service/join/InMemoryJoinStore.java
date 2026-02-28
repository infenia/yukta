/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.join;

import com.infenia.yukta.plugin.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** In-memory implementation of JoinStore using ConcurrentHashMap. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.DoNotUseThreads"})
public class InMemoryJoinStore implements JoinStore {

  private static final int CLEANUP_DELAY = 5;

  private final Map<String, JoinState> store = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread thread = new Thread(r, "join-store-cleanup");
            thread.setDaemon(true);
            return thread;
          });

  /** Default constructor. */
  public InMemoryJoinStore() {
    super();
  }

  /** Initialize the cleanup scheduler. */
  @PostConstruct
  public void init() {
    scheduler.scheduleAtFixedRate(this::cleanup, CLEANUP_DELAY, CLEANUP_DELAY, TimeUnit.SECONDS);
  }

  /** Shutdown the scheduler. */
  @PreDestroy
  public void shutdown() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(CLEANUP_DELAY, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (final InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public Mono<JoinResult> addMessage(
      final String key,
      final String sourceNodeId,
      final Message<?> message,
      final JoinConfig config) {
    return Mono.fromCallable(() -> processAdd(key, sourceNodeId, message, config));
  }

  private JoinResult processAdd(
      final String key,
      final String sourceNodeId,
      final Message<?> message,
      final JoinConfig config) {
    final JoinState state =
        store.compute(key, (k, existing) -> updateState(existing, sourceNodeId, message, config));

    if (state == null) {
      return new JoinResult(JoinResult.Status.OVERFLOW, null);
    }
    if (state.isCompleted()) {
      return new JoinResult(JoinResult.Status.LATE_ARRIVAL, null);
    }

    if (isConditionMet(state, config)) {
      state.setCompleted(true);
      return new JoinResult(JoinResult.Status.COMPLETED, state.getMessages());
    }

    return new JoinResult(JoinResult.Status.WAITING, null);
  }

  private JoinState updateState(
      final JoinState existing,
      final String sourceNodeId,
      final Message<?> message,
      final JoinConfig config) {
    if (existing == null) {
      if (store.size() >= config.maxPendingJoins()) {
        return null;
      }
      final JoinState newState = new JoinState(config.timeoutMs());
      newState.addMessage(sourceNodeId, message);
      return newState;
    }
    if (!existing.isCompleted()) {
      existing.addMessage(sourceNodeId, message);
    }
    return existing;
  }

  @Override
  public void markCompleted(final String key, final long timeoutMs) {
    store.compute(
        key,
        (k, state) -> {
          if (state == null) {
            final JoinState newState = new JoinState(timeoutMs);
            newState.setCompleted(true);
            return newState;
          }
          state.setCompleted(true);
          return state;
        });
  }

  private boolean isConditionMet(final JoinState state, final JoinConfig config) {
    return switch (config.mode()) {
      case "ALL" -> config.expectedAncestors().stream().allMatch(state::hasMessage);
      case "ANY" -> !state.isEmpty();
      case "CUSTOM_COUNT" -> state.getMessageCount() >= config.customCount();
      default -> false;
    };
  }

  private void cleanup() {
    final long now = System.currentTimeMillis();
    final int before = store.size();
    store.entrySet().removeIf(entry -> entry.getValue().getExpirationTime() < now);
    final int after = store.size();
    if (before != after && log.isDebugEnabled()) {
      log.debug("Cleaned up {} expired join entries", before - after);
    }
  }

  @SuppressWarnings("PMD.AvoidUsingVolatile")
  private static class JoinState {
    private final Map<String, Message<?>> messages = new ConcurrentHashMap<>();
    private final long expirationTime;
    private volatile boolean completed;

    /* default */ JoinState(final long timeoutMs) {
      this.expirationTime = System.currentTimeMillis() + timeoutMs;
    }

    /* default */ void addMessage(final String sourceNodeId, final Message<?> message) {
      messages.put(sourceNodeId, message);
    }

    /* default */ boolean hasMessage(final String sourceNodeId) {
      return messages.containsKey(sourceNodeId);
    }

    /* default */ boolean isEmpty() {
      return messages.isEmpty();
    }

    /* default */ int getMessageCount() {
      return messages.size();
    }

    /* default */ Map<String, Message<?>> getMessages() {
      return new ConcurrentHashMap<>(messages);
    }

    /* default */ boolean isCompleted() {
      return completed;
    }

    /* default */ void setCompleted(final boolean completed) {
      this.completed = completed;
    }

    /* default */ long getExpirationTime() {
      return expirationTime;
    }
  }
}
