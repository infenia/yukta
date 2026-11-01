// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where input requests and their answers are kept. The shape is small and reactive so a durable
 * implementation (PostgreSQL) fits behind it; the shared contract tests in the {@code plus} test
 * kit say what every implementation must do.
 *
 * <p>Every change to a request goes through {@link #compareAndSet}, so two changes at once produce
 * one winner. A terminal request never changes.
 */
public interface InputRequestStore {

  /**
   * Stores a request unless a non-terminal request with the same key exists.
   *
   * @param request the new request
   * @return the stored request: the argument, or the existing open request with that key
   */
  Mono<InputRequest> createIfAbsent(InputRequest request);

  /**
   * Reads a request.
   *
   * @param id the request id
   * @return the request, or empty
   */
  Mono<InputRequest> findById(String id);

  /**
   * Reads the newest answered request with a key, for a run that asks again after a restart.
   *
   * @param key the idempotency key
   * @return the answered request, or empty
   */
  Mono<InputRequest> findAnsweredByKey(String key);

  /**
   * Replaces a request if it is still at the expected version.
   *
   * @param id the request id
   * @param expectedVersion the version the caller read
   * @param next the new request; its id is {@code id}
   * @return true if replaced; false when the version moved, the request is unknown or already
   *     terminal
   */
  Mono<Boolean> compareAndSet(String id, long expectedVersion, InputRequest next);

  /**
   * Stores the answer to a request. The first answer stored for a request stays; a second call
   * returns the first.
   *
   * @param response the answer
   * @return the answer that is stored
   */
  Mono<InputResponse> saveResponse(InputResponse response);

  /**
   * Reads the answer to a request.
   *
   * @param requestId the request id
   * @return the answer, or empty
   */
  Mono<InputResponse> findResponse(String requestId);

  /**
   * Lists the requests that are not terminal.
   *
   * @param sessionId only this session, or {@code null} for all
   * @param executionId only this run, or {@code null} for all
   * @return the open requests, oldest first
   */
  Flux<InputRequest> listOpen(@Nullable String sessionId, @Nullable String executionId);

  /**
   * Lists the requests past their deadline.
   *
   * @param now the time to compare with
   * @return non-terminal requests whose deadline is {@code now} or earlier
   */
  Flux<InputRequest> dueBefore(Instant now);

  /**
   * Removes finished requests, and their answers, last changed before a time.
   *
   * @param cutoff the time
   * @return how many requests were removed
   */
  Mono<Long> purgeTerminalOlderThan(Instant cutoff);
}
