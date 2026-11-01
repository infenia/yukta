// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps input requests in memory; they are gone after a restart. At most {@code memoryMax} finished
 * requests (and their answers) are kept: the oldest finished request is evicted first. Requests
 * that are still open are never evicted.
 */
public final class InMemoryInputRequestStore implements InputRequestStore {

  /** The smallest bound on finished requests that is accepted. */
  private static final int MIN_MEMORY_MAX = 1;

  /** Guards the maps: every access runs under it. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The requests by id, oldest first. */
  private final SequencedMap<String, InputRequest> requests = new LinkedHashMap<>();

  /** The answers by request id. */
  private final Map<String, InputResponse> responses = new ConcurrentHashMap<>();

  /** Most finished requests kept. */
  private final int memoryMax;

  /**
   * Creates the store.
   *
   * @param memoryMax most finished requests kept; at least 1
   */
  public InMemoryInputRequestStore(final int memoryMax) {
    this.memoryMax = checkedMax(memoryMax);
  }

  private static int checkedMax(final int memoryMax) {
    if (memoryMax < MIN_MEMORY_MAX) {
      throw new IllegalArgumentException("memoryMax must be at least 1");
    }
    return memoryMax;
  }

  @Override
  public Mono<InputRequest> createIfAbsent(final InputRequest request) {
    return Mono.fromSupplier(
        () ->
            locked(
                () -> {
                  for (final InputRequest existing : requests.values()) {
                    if (!existing.state().isTerminal() && existing.key().equals(request.key())) {
                      return existing;
                    }
                  }
                  requests.put(request.id(), request);
                  return request;
                }));
  }

  @Override
  public Mono<InputRequest> findById(final String id) {
    return Mono.fromSupplier(() -> locked(() -> requests.get(id)));
  }

  @Override
  public Mono<InputRequest> findAnsweredByKey(final String key) {
    return Mono.fromSupplier(
        () ->
            locked(
                () -> {
                  InputRequest found = null;
                  for (final InputRequest candidate : requests.values()) {
                    if (candidate.state() == InputState.ANSWERED && candidate.key().equals(key)) {
                      found = candidate;
                    }
                  }
                  return found;
                }));
  }

  @Override
  public Mono<Boolean> compareAndSet(
      final String id, final long expectedVersion, final InputRequest next) {
    return Mono.fromSupplier(
        () ->
            locked(
                () -> {
                  final InputRequest current = requests.get(id);
                  if (current == null
                      || current.state().isTerminal()
                      || current.version() != expectedVersion
                      || !id.equals(next.id())) {
                    return false;
                  }
                  requests.put(id, next);
                  if (next.state().isTerminal()) {
                    // drops the oldest finished requests beyond the bound
                    final List<String> finished =
                        requests.values().stream()
                            .filter(r -> r.state().isTerminal())
                            .map(InputRequest::id)
                            .toList();
                    for (int i = 0; i < finished.size() - memoryMax; i++) {
                      requests.remove(finished.get(i));
                      responses.remove(finished.get(i));
                    }
                  }
                  return true;
                }));
  }

  @Override
  public Mono<InputResponse> saveResponse(final InputResponse response) {
    return Mono.fromSupplier(
        () -> locked(() -> responses.computeIfAbsent(response.requestId(), _ -> response)));
  }

  @Override
  public Mono<InputResponse> findResponse(final String requestId) {
    return Mono.fromSupplier(() -> locked(() -> responses.get(requestId)));
  }

  @Override
  public Flux<InputRequest> listOpen(
      final @Nullable String sessionId, final @Nullable String executionId) {
    return Flux.defer(
        () ->
            Flux.fromIterable(
                locked(
                    () ->
                        requests.values().stream()
                            .filter(
                                request ->
                                    !request.state().isTerminal()
                                        && (sessionId == null
                                            || sessionId.equals(request.sessionId()))
                                        && (executionId == null
                                            || executionId.equals(request.executionId())))
                            .toList())));
  }

  @Override
  public Flux<InputRequest> dueBefore(final Instant now) {
    return Flux.defer(
        () ->
            Flux.fromIterable(
                locked(
                    () ->
                        requests.values().stream()
                            .filter(request -> isDue(request, now))
                            .toList())));
  }

  /** Tells whether an open request has a deadline that is not after the given time. */
  private static boolean isDue(final InputRequest request, final Instant now) {
    final Instant deadline = request.deadline();
    return !request.state().isTerminal() && deadline != null && !deadline.isAfter(now);
  }

  @Override
  public Mono<Long> purgeTerminalOlderThan(final Instant cutoff) {
    return Mono.fromSupplier(
        () ->
            locked(
                () -> {
                  final List<String> old =
                      requests.values().stream()
                          .filter(r -> r.state().isTerminal() && r.updatedAt().isBefore(cutoff))
                          .map(InputRequest::id)
                          .toList();
                  for (final String id : old) {
                    requests.remove(id);
                    responses.remove(id);
                  }
                  return (long) old.size();
                }));
  }

  /** Runs the action under the lock, so the maps are never seen half-changed. */
  private <T> T locked(final Supplier<T> action) {
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
