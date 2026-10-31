// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Keeps the recent history of a curated set of meters.
 *
 * <p>Every interval it reads the allowed meters into a fine ring buffer, and once a minute also
 * into a coarse one, so the dashboard can chart the last hour closely and the last day roughly.
 * Both buffers have a fixed size, and at most {@code maxSeries} series are kept, so memory stays
 * bounded. History is lost on restart.
 */
@Slf4j
@Component
// All state is guarded by this one lock, so plain maps and method-level synchronization are right.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidSynchronizedAtMethodLevel",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
public class MetricsSampler {

  /** Milliseconds in a minute. */
  private static final long MINUTE = 60_000L;

  /** Milliseconds in a second. */
  private static final double MILLIS_PER_SECOND = 1000.0;

  /** Percentiles read from timers that have them, and their series suffix. */
  private static final Map<Double, String> PERCENTILES =
      Map.of(0.5, "|p50", 0.95, "|p95", 0.99, "|p99");

  /** Where the values come from. */
  private final MeterRegistry registry;

  /** The settings. */
  private final MetricsProperties properties;

  /** Samples at the sampling interval. */
  private final Deque<MetricSample> fine = new ArrayDeque<>();

  /** Samples taken once a minute. */
  private final Deque<MetricSample> coarse = new ArrayDeque<>();

  /** Newest sample to every stream subscriber. */
  private final Sinks.Many<MetricSample> live = Sinks.many().multicast().directBestEffort();

  /** Cumulative values at the previous sample, to turn counters and timers into rates. */
  private Map<String, double[]> previous = new HashMap<>();

  /** When the previous sample was taken. */
  private long previousTime;

  /** The minute of the last coarse sample. */
  private long lastMinute = -1;

  /** The running timer, null before start. */
  private Disposable ticker;

  /**
   * Creates the sampler.
   *
   * @param registry where the values come from
   * @param properties the settings
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects the shared registry, which is meant to be held")
  public MetricsSampler(final MeterRegistry registry, final MetricsProperties properties) {
    this.registry = registry;
    this.properties = properties;
  }

  /** Starts sampling when enabled. */
  @PostConstruct
  /* default */ void start() {
    if (properties.enabled()) {
      ticker =
          Flux.interval(properties.interval()).subscribe(_ -> sample(System.currentTimeMillis()));
    }
  }

  /** Stops sampling. */
  @PreDestroy
  /* default */ void stop() {
    if (ticker != null) {
      ticker.dispose();
    }
  }

  /**
   * Reads the allowed meters once and stores the result. A meter that cannot be read is skipped.
   *
   * @param now the sample time in epoch milliseconds
   */
  public synchronized void sample(final long now) {
    final Map<String, Double> values = new TreeMap<>();
    final Map<String, double[]> raw = new HashMap<>();
    final double seconds = previousTime == 0 ? 0 : (now - previousTime) / MILLIS_PER_SECOND;
    for (final Meter meter : registry.getMeters()) {
      if (values.size() >= properties.maxSeries()) {
        break;
      }
      if (allowed(meter.getId().getName())) {
        try {
          read(meter, seconds, values, raw);
        } catch (
            @SuppressWarnings("PMD.AvoidCatchingGenericException")
            final RuntimeException e) {
          log.atDebug().setCause(e).addKeyValue("meter", meter.getId()).log("Skipped meter");
        }
      }
    }
    previous = raw; // meters that disappeared drop out of the baseline here
    previousTime = now;
    final MetricSample sample = new MetricSample(now, Map.copyOf(values));
    push(fine, sample, properties.fineRetention().dividedBy(properties.interval()));
    final long minute = now / MINUTE;
    if (minute != lastMinute) {
      lastMinute = minute;
      push(coarse, sample, properties.coarseRetention().dividedBy(Duration.ofMinutes(1)));
    }
    live.tryEmitNext(sample);
  }

  private boolean allowed(final String name) {
    return properties.allowList().stream().anyMatch(name::startsWith);
  }

  private void read(
      final Meter meter,
      final double seconds,
      final Map<String, Double> values,
      final Map<String, double[]> raw) {
    final String key = key(meter.getId());
    switch (meter) {
      case Gauge gauge -> put(values, key, gauge.value());
      case Counter counter -> rate(key, counter.count(), seconds, values, raw);
      case FunctionCounter counter -> rate(key, counter.count(), seconds, values, raw);
      case Timer timer -> readTimer(timer, key, seconds, values, raw);
      default -> {
        // other meter types are not charted
      }
    }
  }

  private void readTimer(
      final Timer timer,
      final String key,
      final double seconds,
      final Map<String, Double> values,
      final Map<String, double[]> raw) {
    final double count = timer.count();
    final double total = timer.totalTime(TimeUnit.MILLISECONDS);
    final double[] before = previous.get(key);
    raw.put(key, new double[] {count, total});
    final double deltaCount = before == null ? 0 : Math.max(0, count - before[0]);
    final double deltaTotal = before == null ? 0 : Math.max(0, total - before[1]);
    put(values, key + "|rate", seconds > 0 ? deltaCount / seconds : 0);
    put(values, key + "|mean", deltaCount > 0 ? deltaTotal / deltaCount : 0);
    put(values, key + "|max", timer.max(TimeUnit.MILLISECONDS));
    for (final ValueAtPercentile p : timer.takeSnapshot().percentileValues()) {
      final String suffix = PERCENTILES.get(p.percentile());
      if (suffix != null) {
        put(values, key + suffix, p.value(TimeUnit.MILLISECONDS));
      }
    }
  }

  private void rate(
      final String key,
      final double count,
      final double seconds,
      final Map<String, Double> values,
      final Map<String, double[]> raw) {
    final double[] before = previous.get(key);
    raw.put(key, new double[] {count});
    // a counter that went down was reset: report no rate for that tick
    final double delta = before == null ? 0 : Math.max(0, count - before[0]);
    put(values, key + "|rate", seconds > 0 ? delta / seconds : 0);
  }

  private static void put(final Map<String, Double> values, final String key, final double v) {
    values.put(key, Double.isFinite(v) ? v : 0.0);
  }

  private static String key(final Meter.Id id) {
    if (id.getTags().isEmpty()) {
      return id.getName();
    }
    final StringBuilder out = new StringBuilder(id.getName()).append('{');
    id.getTags().forEach(t -> out.append(t.getKey()).append('=').append(t.getValue()).append(','));
    out.setLength(out.length() - 1);
    return out.append('}').toString();
  }

  private static void push(
      final Deque<MetricSample> buffer, final MetricSample sample, final long capacity) {
    buffer.addLast(sample);
    while (buffer.size() > Math.max(1, capacity)) {
      buffer.removeFirst();
    }
  }

  /**
   * The newest sample.
   *
   * @return the sample, empty before the first tick
   */
  public synchronized Mono<MetricSample> snapshot() {
    return Mono.justOrEmpty(fine.peekLast());
  }

  /**
   * The series that start with any of the given names, within the range.
   *
   * @param names series keys, or meter names to take all their series
   * @param range how far back, up to the coarse retention
   * @param now the current epoch milliseconds
   * @return series key to ordered points
   */
  public synchronized Map<String, List<Point>> series(
      final List<String> names, final Duration range, final long now) {
    final Deque<MetricSample> source =
        range.compareTo(properties.fineRetention()) <= 0 ? fine : coarse;
    final long from = now - range.toMillis();
    final Map<String, List<Point>> out = new TreeMap<>();
    for (final MetricSample s : source) {
      if (s.time() >= from) {
        s.values()
            .forEach(
                (k, v) -> {
                  if (names.stream().anyMatch(k::startsWith)) {
                    out.computeIfAbsent(k, _ -> new ArrayList<>()).add(new Point(s.time(), v));
                  }
                });
      }
    }
    return out;
  }

  /**
   * The series keys known now.
   *
   * @return sorted keys of the newest sample
   */
  public synchronized List<String> catalog() {
    final MetricSample last = fine.peekLast();
    return last == null ? List.of() : last.values().keySet().stream().sorted().toList();
  }

  /**
   * Samples as they are taken, starting with the newest one so a reconnecting client has a value.
   *
   * @return the samples
   */
  public Flux<MetricSample> stream() {
    return Flux.concat(snapshot(), live.asFlux());
  }

  /**
   * One value of a series.
   *
   * @param time epoch milliseconds
   * @param value the value
   */
  public record Point(long time, double value) {}
}
