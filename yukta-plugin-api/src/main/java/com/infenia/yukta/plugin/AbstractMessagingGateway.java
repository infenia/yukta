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
package com.infenia.yukta.plugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Base implementation of a Messaging Gateway providing common logic for mapping domain objects and
 * managing asynchronous correlation.
 *
 * @param <T> request type
 * @param <R> response type
 * @param <D> domain request type
 * @param <S> domain response type
 */
@Slf4j
@SuppressWarnings({"PMD.TypeParameterNamingConventions", "PMD.DoNotUseThreads"})
public abstract class AbstractMessagingGateway<T, R, D, S> implements MessagingGateway {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
  private static final long CLEANUP_MS = 5000;

  private final MessageMapper<T, D> requestMapper;
  private final MessageMapper<R, S> responseMapper;

  /** Sinks for correlation management. */
  private final Map<String, PendingReply<R>> pendingReplies = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread thread =
                new Thread(r, "gateway-cleanup-" + UUID.randomUUID().toString().substring(0, 8));
            thread.setDaemon(true);
            return thread;
          });

  /**
   * Constructor for the abstract messaging gateway.
   *
   * @param requestMapper mapper for converting domain requests into message envelopes
   * @param responseMapper mapper for converting message responses back into domain objects
   */
  protected AbstractMessagingGateway(
      final MessageMapper<T, D> requestMapper, final MessageMapper<R, S> responseMapper) {
    this.requestMapper = requestMapper;
    this.responseMapper = responseMapper;
    this.scheduler.scheduleAtFixedRate(
        this::purgeExpiredReplies, CLEANUP_MS, CLEANUP_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Domain-specific send-and-receive method.
   *
   * @param domainRequest the request as a domain object
   * @param originalMessage the original message to preserve headers (context)
   * @return a Mono of the domain response object
   */
  public Mono<S> exchange(final D domainRequest, final Message<?> originalMessage) {
    final Message<T> request = requestMapper.fromDomain(domainRequest, originalMessage);
    return this.<T, R>sendAndReceive(request).map(responseMapper::toDomain);
  }

  @Override
  public <T1, R1> Mono<Message<R1>> sendAndReceive(final Message<T1> request) {
    final String correlationId =
        request.getCorrelationId() != null
            ? request.getCorrelationId()
            : UUID.randomUUID().toString();

    final Message<T1> correlatedRequest = request.withCorrelationId(correlationId);
    final Sinks.One<Message<R>> replySink = Sinks.one();

    pendingReplies.put(correlationId, new PendingReply<>(replySink, System.currentTimeMillis()));

    return dispatch(correlatedRequest)
        .then(replySink.asMono())
        .timeout(DEFAULT_TIMEOUT)
        .doFinally(signalType -> pendingReplies.remove(correlationId))
        .onErrorMap(
            e -> {
              if (e instanceof TimeoutException) {
                return new WorkflowExecutionException(
                    "Gateway request timed out after " + DEFAULT_TIMEOUT.toSeconds() + " seconds",
                    e);
              }
              if (e instanceof WorkflowExecutionException) {
                return e;
              }
              return new WorkflowExecutionException(
                  "Gateway execution failed: " + e.getMessage(), e);
            })
        .map(
            msg -> {
              @SuppressWarnings("unchecked")
              final Message<R1> casted = (Message<R1>) msg;
              return casted;
            });
  }

  /**
   * Handle an incoming response message and route it to the correct pending request.
   *
   * @param response the incoming response message
   */
  protected void handleResponse(final Message<R> response) {
    final String correlationId = response.getCorrelationId();
    if (correlationId == null) {
      if (log.isWarnEnabled()) {
        log.warn(
            "Received response without correlationId, dropping message: {}",
            response.getMessageId());
      }
      return;
    }

    final PendingReply<R> pending = pendingReplies.get(correlationId);
    if (pending != null) {
      pending.sink().tryEmitValue(response);
    } else if (log.isWarnEnabled()) {
      log.warn("No pending request found for correlationId: {}, dropping response", correlationId);
    }
  }

  private void purgeExpiredReplies() {
    final long now = System.currentTimeMillis();
    final long timeoutMs = DEFAULT_TIMEOUT.toMillis();
    // Use a grace period of 5 seconds for the background purge
    pendingReplies
        .entrySet()
        .removeIf(
            entry -> {
              if (now - entry.getValue().createdAt() > timeoutMs + 5000) {
                entry
                    .getValue()
                    .sink()
                    .tryEmitError(
                        new WorkflowExecutionException(
                            "Gateway request expired and was purged from memory"));
                return true;
              }
              return false;
            });
  }

  /** Shutdown the gateway and its background tasks. */
  public void shutdown() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (final InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Abstract dispatch method to be implemented by sub-classes (e.g., Kafka, HTTP, Local).
   *
   * @param <T1> request payload type
   * @param message the message to dispatch
   * @return a Mono that completes when the message is dispatched
   */
  protected abstract <T1> Mono<Void> dispatch(Message<T1> message);

  @Override
  public <T1> Mono<Void> send(final Message<T1> request) {
    return dispatch(request)
        .onErrorMap(
            e -> {
              if (e instanceof WorkflowExecutionException) {
                return e;
              }
              return new WorkflowExecutionException("Failed to send message: " + e.getMessage(), e);
            });
  }

  @Override
  public abstract <R1> Flux<Message<R1>> receive();

  private record PendingReply<R>(Sinks.One<Message<R>> sink, long createdAt) {}
}
