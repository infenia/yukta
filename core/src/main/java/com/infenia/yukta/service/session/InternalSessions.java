// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session;

import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Tells whether a session is <em>internal</em>: a session that holds the workflows of supervisors
 * and other machinery, hidden from the default lists and from the events supervisors watch. A
 * session is internal when its tags contain {@value #TAG}{@code =true}; only ADMIN can define
 * sessions, so only ADMIN can set it, and the tag is kept as the store keeps tags (set once on the
 * database stores, replaced by the file and memory stores).
 *
 * <p>The answer is cached for {@value #TTL_SECONDS} s and dropped when this server saves or deletes
 * the session, so another server's change shows up within that time.
 */
@Component
@RequiredArgsConstructor
public class InternalSessions {

  /** The tag that makes a session internal. */
  public static final String TAG = "yukta.internal";

  /** How long an answer is kept. */
  static final int TTL_SECONDS = 30;

  /** Where the tags are read from. */
  private final SessionConfigStore store;

  /** The time source, replaceable in tests. */
  private Clock clock = Clock.systemUTC();

  /** Answers by session id. */
  private final Map<String, Answer> cache = new ConcurrentHashMap<>();

  /**
   * Replaces the clock.
   *
   * @param newClock the clock
   */
  void setClock(final Clock newClock) {
    this.clock = newClock;
  }

  /**
   * Whether a session is internal. A session that cannot be read counts as internal, so a store
   * outage hides rather than reveals.
   *
   * @param sessionId the session, or {@code null} (never internal)
   * @return true if internal
   */
  public Mono<Boolean> isInternal(final String sessionId) {
    if (sessionId == null) {
      return Mono.just(false);
    }
    final Answer known = cache.get(sessionId);
    final Instant now = clock.instant();
    if (known != null && known.until().isAfter(now)) {
      return Mono.just(known.internal());
    }
    return store
        .getTags(sessionId)
        .map(tags -> "true".equalsIgnoreCase(tags.get(TAG)))
        .defaultIfEmpty(false)
        .onErrorReturn(true)
        .doOnNext(
            internal ->
                cache.put(
                    sessionId, new Answer(internal, now.plus(Duration.ofSeconds(TTL_SECONDS)))));
  }

  /**
   * Forgets the answer for a session, after it was saved or deleted here.
   *
   * @param sessionId the session
   */
  public void invalidate(final String sessionId) {
    cache.remove(sessionId);
  }

  /** One cached answer. */
  private record Answer(boolean internal, Instant until) {}
}
