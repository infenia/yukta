// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.control.ControlActor;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.experimental.UtilityClass;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Server-sent-event plumbing shared by the streaming endpoints.
 *
 * <p>Two limits keep a client (or a UI tab that reconnects in a loop) from holding the server's
 * memory and connections: at most {@code yukta.streams.max-concurrent} streams are open at once
 * (default 500; the next one is answered with 429), and a stream is closed after {@code
 * yukta.streams.max-lifetime} (default 1h; a browser reconnects on its own and the stream replays).
 * {@code yukta.streams.max-per-key} (default 50) caps the streams of one named key, so one client
 * cannot take every slot. All are system properties; 0 turns a limit off.
 */
@UtilityClass
public class SseStreams {

  /** System property: most streams open at once. */
  public static final String MAX_CONCURRENT_PROPERTY = "yukta.streams.max-concurrent";

  /** System property: longest a stream stays open, such as {@code 30m} or {@code 1h}. */
  public static final String MAX_LIFETIME_PROPERTY = "yukta.streams.max-lifetime";

  /** System property: most streams one named key has open at once. */
  public static final String MAX_PER_KEY_PROPERTY = "yukta.streams.max-per-key";

  /** Streams allowed per key when {@value #MAX_PER_KEY_PROPERTY} is not set. */
  private static final int DEFAULT_MAX_PER_KEY = 50;

  /** Streams open now per caller; one counter per configured key, so the map stays small. */
  private static final Map<String, AtomicInteger> OPEN_BY_CALLER = new ConcurrentHashMap<>();

  /** Streams that are open now. */
  private static final AtomicInteger OPEN = new AtomicInteger();

  /** Streams allowed at once when {@value #MAX_CONCURRENT_PROPERTY} is not set. */
  private static final int DEFAULT_MAX_CONCURRENT = 500;

  /** How often an idle stream sends a keep-alive comment so proxies do not close it. */
  public static final Duration HEARTBEAT = Duration.ofSeconds(15);

  /**
   * Wrap a stream as events with sequential IDs and a keep-alive.
   *
   * <p>Event IDs count from 0 within the stream. A reconnecting client sends the last ID it saw in
   * {@code Last-Event-ID}; the replayed stream then skips everything up to that ID instead of
   * delivering it twice.
   *
   * @param data the payloads
   * @param lastEventId the {@code Last-Event-ID} header, or null on a fresh connection
   * @param heartbeat interval between keep-alive comments
   * @param <T> payload type
   * @return the events
   */
  public static <T> Flux<ServerSentEvent<T>> events(
      final Flux<T> data, final String lastEventId, final Duration heartbeat) {
    final long skip = parse(lastEventId) + 1;
    return Flux.deferContextual(
        context -> {
          final int max = Integer.getInteger(MAX_CONCURRENT_PROPERTY, DEFAULT_MAX_CONCURRENT);
          final int perKey = Integer.getInteger(MAX_PER_KEY_PROPERTY, DEFAULT_MAX_PER_KEY);
          final AtomicInteger mine =
              context
                  .<String>getOrEmpty(ControlActor.CONTEXT_KEY)
                  .map(caller -> OPEN_BY_CALLER.computeIfAbsent(caller, _ -> new AtomicInteger()))
                  .orElseGet(AtomicInteger::new);
          final boolean full = OPEN.incrementAndGet() > max && max > 0;
          if (mine.incrementAndGet() > perKey && perKey > 0 || full) {
            release(mine);
            return Flux.<ServerSentEvent<T>>error(
                new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many open streams, try again later"));
          }
          final Sinks.Empty<Void> finished = Sinks.empty();
          final Flux<ServerSentEvent<T>> events =
              data.index()
                  .filter(indexed -> indexed.getT1() >= skip)
                  .map(
                      indexed ->
                          ServerSentEvent.<T>builder()
                              .id(String.valueOf(indexed.getT1()))
                              .data(indexed.getT2())
                              .build())
                  .doFinally(_ -> finished.tryEmitEmpty());
          final Flux<ServerSentEvent<T>> keepAlive =
              Flux.interval(heartbeat)
                  .map(_ -> ServerSentEvent.<T>builder().comment("ping").build())
                  .takeUntilOther(finished.asMono());
          final Flux<ServerSentEvent<T>> merged = Flux.merge(events, keepAlive);
          return (lifetime().isZero() ? merged : merged.take(lifetime()))
              .doFinally(_ -> release(mine));
        });
  }

  /** Gives back a stream's slots. */
  private static void release(final AtomicInteger mine) {
    OPEN.decrementAndGet();
    mine.decrementAndGet();
  }

  private static Duration lifetime() {
    final String configured = System.getProperty(MAX_LIFETIME_PROPERTY);
    return configured == null
        ? Duration.ofHours(1)
        : DurationStyle.detectAndParse(configured.strip());
  }

  private static long parse(final String lastEventId) {
    long parsed = -1;
    if (lastEventId != null) {
      try {
        parsed = Long.parseLong(lastEventId.trim());
      } catch (final NumberFormatException e) {
        parsed = -1;
      }
    }
    return parsed;
  }
}
