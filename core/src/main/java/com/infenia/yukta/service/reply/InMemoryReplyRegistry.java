// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.reply;

import com.infenia.yukta.plugin.gateway.ReplyGateway;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The callers waiting for a run's reply, by reply key.
 *
 * <p>ponytail: in memory, so a reply reaches only a caller on the server that runs the run; a
 * shared store is needed once runs move between servers.
 */
@Component
@NoArgsConstructor
public class InMemoryReplyRegistry implements ReplyGateway {

  /** The open waits. */
  private final Map<String, Sinks.One<Object>> waits = new ConcurrentHashMap<>();

  /**
   * Opens a wait before the run starts, so a fast reply is not missed. Close it with {@link #close}
   * whatever happens.
   *
   * @param key a new, unguessable reply key
   * @return completes with the first reply
   */
  public Mono<Object> open(final String key) {
    final Sinks.One<Object> sink = Sinks.one();
    waits.put(key, sink);
    return sink.asMono();
  }

  /**
   * Ends a wait; a later reply to the key is ignored.
   *
   * @param key the reply key
   */
  public void close(final String key) {
    waits.remove(key);
  }

  /**
   * Number of open waits.
   *
   * @return the count
   */
  public int waiting() {
    return waits.size();
  }

  @Override
  public boolean reply(final String key, final Object payload) {
    final Sinks.One<Object> sink = key == null ? null : waits.get(key);
    return sink != null && sink.tryEmitValue(payload).isSuccess();
  }
}
