// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import java.time.Duration;
import lombok.experimental.UtilityClass;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Server-sent-event plumbing shared by the streaming endpoints. */
@UtilityClass
public class SseStreams {

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
    return Flux.defer(
        () -> {
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
          return Flux.merge(events, keepAlive);
        });
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
