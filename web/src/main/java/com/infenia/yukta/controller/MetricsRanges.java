// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The ranges the metrics endpoints accept, and the reads they wrap. */
@UtilityClass
class MetricsRanges {

  /** Ranges a client may ask for. */
  private static final Map<String, Duration> RANGES =
      Map.of(
          "15m", Duration.ofMinutes(15),
          "1h", Duration.ofHours(1),
          "6h", Duration.ofHours(6),
          "24h", Duration.ofHours(24));

  /** Reads with a range, or answers 400 when the range is not one of the four. */
  /* default */ static <T> Mono<ApiResponse<T>> withRange(
      final String range, final Function<Duration, T> read, final String text) {
    final Duration duration = RANGES.get(range);
    return duration == null
        ? Mono.error(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "range must be 15m, 1h, 6h or 24h"))
        // off the event loop: a 24 h read scans every sample
        : Mono.fromSupplier(() -> ApiResponse.success(200, text, read.apply(duration)))
            .subscribeOn(Schedulers.boundedElastic());
  }
}
