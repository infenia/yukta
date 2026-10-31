// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.metrics.MetricSample;
import com.infenia.yukta.service.metrics.MetricsSampler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Recent history of the server's own metrics, for the Control dashboard. */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
@Tag(name = "Metrics API", description = "Recent history of server metrics")
@RegisterReflectionForBinding({
  MetricSample.class,
  MetricsSampler.Point.class,
  MetricsController.Series.class,
  MetricsController.Catalog.class
})
public class MetricsController {

  /** Ranges a client may ask for. */
  private static final Map<String, Duration> RANGES =
      Map.of(
          "15m", Duration.ofMinutes(15),
          "1h", Duration.ofHours(1),
          "6h", Duration.ofHours(6),
          "24h", Duration.ofHours(24));

  /** The sampler. */
  private final MetricsSampler sampler;

  /**
   * Series over a range.
   *
   * @param range the range asked for
   * @param series series key to ordered points
   */
  public record Series(String range, Map<String, List<MetricsSampler.Point>> series) {}

  /**
   * The series that exist.
   *
   * @param series series keys in the newest sample
   */
  public record Catalog(List<String> series) {}

  /**
   * List the sampled series.
   *
   * @return the series keys of the newest sample
   */
  @GetMapping("/catalog")
  @Operation(summary = "List sampled metric series")
  public Mono<ApiResponse<Catalog>> catalog() {
    return Mono.fromSupplier(
        () -> ApiResponse.success(200, "Metric catalog retrieved", new Catalog(sampler.catalog())));
  }

  /**
   * History of the named series.
   *
   * @param names meter names or series keys, comma-separated; a meter name selects all its series
   * @param range 15m, 1h, 6h or 24h
   * @return the points of each series
   */
  @GetMapping("/series")
  @Operation(summary = "Metric history", description = "Points of the named series over a range")
  public Mono<ApiResponse<Series>> series(
      @RequestParam final List<String> names,
      @RequestParam(defaultValue = "1h") final String range) {
    final Duration duration = RANGES.get(range);
    if (duration == null) {
      return Mono.error(
          new ResponseStatusException(HttpStatus.BAD_REQUEST, "range must be 15m, 1h, 6h or 24h"));
    }
    return Mono.fromSupplier(
        () ->
            ApiResponse.success(
                200,
                "Metric series retrieved",
                new Series(range, sampler.series(names, duration, System.currentTimeMillis()))));
  }

  /**
   * The newest sample.
   *
   * @return every sampled value; time 0 and no values before the first sample
   */
  @GetMapping("/snapshot")
  @Operation(summary = "Newest metric sample")
  public Mono<ApiResponse<MetricSample>> snapshot() {
    return sampler
        .snapshot()
        .defaultIfEmpty(new MetricSample(0, Map.of()))
        .map(sample -> ApiResponse.success(200, "Metric snapshot retrieved", sample));
  }

  /**
   * One sample per tick, starting with the newest.
   *
   * @param lastEventId the last event ID the client saw; ignored, the stream carries no history
   * @return the samples with a keep-alive comment
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "Stream metric samples")
  public Flux<ServerSentEvent<MetricSample>> stream(
      @RequestHeader(name = "Last-Event-ID", required = false) final String lastEventId) {
    // a reconnect starts again at the current sample, so IDs restart and nothing is skipped
    return SseStreams.events(sampler.stream(), null, SseStreams.HEARTBEAT);
  }
}
