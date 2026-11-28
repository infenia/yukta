// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static com.infenia.yukta.controller.MetricsRanges.withRange;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.metrics.MetricSample;
import com.infenia.yukta.service.metrics.MetricsSampler;
import com.infenia.yukta.service.metrics.RunAnalytics;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
  MetricsController.Catalog.class,
  RunAnalytics.Annotation.class,
  RunAnalytics.HeatCell.class,
  RunAnalytics.ModuleRow.class,
  RunAnalytics.NodeRow.class,
  RunAnalytics.LiveRun.class
})
public class MetricsController {

  /** The sampler. */
  private final MetricsSampler sampler;

  /** The per-session and per-workflow aggregates. */
  private final RunAnalytics analytics;

  /** Knows how many nodes a running run has. */
  private final TaskTrackerService tracker;

  /**
   * Series over a range.
   *
   * @param range the range asked for
   * @param series series key to ordered points
   */
  public record Series(String range, Map<String, List<MetricsSampler.Point>> series) {

    /** Keeps an immutable copy of the series. */
    public Series {
      series = Map.copyOf(series);
    }
  }

  /**
   * The series that exist.
   *
   * @param series series keys in the newest sample
   */
  public record Catalog(List<String> series) {

    /** Keeps an immutable copy of the keys. */
    public Catalog {
      series = List.copyOf(series);
    }
  }

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
   * @param scope {@code global} (default), {@code session:<id>} or {@code workflow:<id>}; a scope
   *     other than global has only the run series ({@code runs.*}, {@code duration.*}, {@code
   *     queue.*}), and an unknown scope has none
   * @return the points of each series
   */
  @GetMapping("/series")
  @Operation(summary = "Metric history", description = "Points of the named series over a range")
  public Mono<ApiResponse<Series>> series(
      @RequestParam final List<String> names,
      @RequestParam(defaultValue = "1h") final String range,
      @RequestParam(defaultValue = RunAnalytics.GLOBAL) final String scope) {
    return withRange(
        range,
        duration -> {
          final Map<String, List<MetricsSampler.Point>> series = new ConcurrentHashMap<>();
          if (RunAnalytics.GLOBAL.equals(scope)) {
            series.putAll(sampler.series(names, duration, System.currentTimeMillis()));
          }
          series.putAll(analytics.series(scope, names, duration));
          return new Series(range, series);
        },
        "Metric series retrieved");
  }

  /**
   * The newest sample.
   *
   * @param scope {@code global} (default), {@code session:<id>} or {@code workflow:<id>}; a scope
   *     other than global has only the run aggregates of the last hour
   * @return every sampled value; time 0 and no values before the first sample
   */
  @GetMapping("/snapshot")
  @Operation(summary = "Newest metric sample")
  public Mono<ApiResponse<MetricSample>> snapshot(
      @RequestParam(defaultValue = RunAnalytics.GLOBAL) final String scope) {
    final Mono<MetricSample> sampled =
        RunAnalytics.GLOBAL.equals(scope)
            ? sampler.snapshot().defaultIfEmpty(new MetricSample(0, Map.of()))
            : Mono.fromSupplier(() -> new MetricSample(System.currentTimeMillis(), Map.of()));
    return sampled.map(sample -> {
      final Map<String, Double> values = new ConcurrentHashMap<>(sample.values());
      values.putAll(analytics.snapshot(scope));
      return ApiResponse.success(
          200, "Metric snapshot retrieved", new MetricSample(sample.time(), values));
    });
  }

  /**
   * Runs that ended per minute, for the run heat strip.
   *
   * @param range 15m, 1h, 6h or 24h
   * @param scope the scope
   * @return one cell per minute that had runs, oldest first
   */
  @GetMapping("/heatmap")
  @Operation(summary = "Runs per minute", description = "Runs and failures per minute")
  public Mono<ApiResponse<List<RunAnalytics.HeatCell>>> heatmap(
      @RequestParam(defaultValue = "24h") final String range,
      @RequestParam(defaultValue = RunAnalytics.GLOBAL) final String scope) {
    return withRange(
        range, duration -> analytics.heatmap(scope, duration), "Run heat map retrieved");
  }

  /**
   * How each plugin type did.
   *
   * @param range 15m, 1h, 6h or 24h
   * @param scope the scope
   * @return one row per plugin type, busiest first
   */
  @GetMapping("/modules")
  @Operation(summary = "Plugin leaderboard", description = "Executions, failures, mean duration")
  public Mono<ApiResponse<List<RunAnalytics.ModuleRow>>> modules(
      @RequestParam(defaultValue = "1h") final String range,
      @RequestParam(defaultValue = RunAnalytics.GLOBAL) final String scope) {
    return withRange(
        range, duration -> analytics.modules(scope, duration), "Module stats retrieved");
  }

  /**
   * How each node of each workflow did, slowest first.
   *
   * @param range 15m, 1h, 6h or 24h
   * @param scope the scope: the server, a session or a workflow
   * @param limit most rows, 1 to 100
   * @return one row per node, by 95th percentile duration
   */
  @GetMapping("/nodes")
  @Operation(
      summary = "Slowest nodes",
      description = "Runs, failures and duration percentiles per node of a workflow of a session")
  public Mono<ApiResponse<List<RunAnalytics.NodeRow>>> nodes(
      @RequestParam(defaultValue = "1h") final String range,
      @RequestParam(defaultValue = RunAnalytics.GLOBAL) final String scope,
      @RequestParam(defaultValue = "10") final int limit) {
    return withRange(
        range,
        duration -> analytics.nodes(scope, duration, Math.clamp(limit, 1, 100)),
        "Node stats retrieved");
  }

  /**
   * Control commands within the range, for marking charts.
   *
   * @param range 15m, 1h, 6h or 24h
   * @param scope the scope
   * @return the commands, oldest first
   */
  @GetMapping("/annotations")
  @Operation(summary = "Control annotations", description = "Control commands, applied or rejected")
  public Mono<ApiResponse<List<RunAnalytics.Annotation>>> annotations(
      @RequestParam(defaultValue = "1h") final String range,
      @RequestParam(defaultValue = RunAnalytics.GLOBAL) final String scope) {
    return withRange(
        range, duration -> analytics.annotations(scope, duration), "Annotations retrieved");
  }

  /**
   * The runs in progress.
   *
   * @return each running run with its progress and its workflow's usual p95 duration
   */
  @GetMapping("/runs/live")
  @Operation(summary = "Live runs", description = "Running runs with progress and p95 baseline")
  public Mono<ApiResponse<List<RunAnalytics.LiveRun>>> liveRuns() {
    return Mono.fromSupplier(() ->
        ApiResponse.success(
            200,
            "Live runs retrieved",
            analytics.liveRuns().stream()
                .map(run -> {
                  final WorkflowProgress progress =
                      tracker.getProgressByExecutionId(run.executionId());
                  return progress == null ? run : run.withNodesTotal(progress.tasks().size());
                })
                .toList()));
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
