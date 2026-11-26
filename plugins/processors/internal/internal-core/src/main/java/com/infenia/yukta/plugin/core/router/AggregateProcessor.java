// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Collects messages into batches and emits one message {@code {items:[...], count:n,
 * sources:[...]}} each, where {@code sources} names the node each item came from.
 *
 * <p>{@code by} picks how messages are grouped: {@code count} (default) batches any stream by
 * {@code size}; {@code sequence} joins what a SPLITTER split, one group per {@code sequenceId},
 * complete at {@code sequenceSize} and ordered by {@code sequenceNumber}; {@code correlation} joins
 * {@code size} messages that share a {@code correlationId}. A group mode adds {@code complete} (and
 * {@code missing} for {@code sequence}) to the payload.
 */
@Component
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class AggregateProcessor implements ProcessorPlugin {

  /** Size. */
  private static final String SIZE = "size";

  /** Timeout ms. */
  private static final String TIMEOUT_MS = "timeoutMs";

  /** Grouping mode. */
  private static final String BY = "by";

  /** The modes of {@code by}. */
  private enum By {
    COUNT,
    SEQUENCE,
    CORRELATION
  }

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

  /** Creates the plugin without metrics. */
  public AggregateProcessor() {
    this.reporter = PluginMetricsReporter.NOOP;
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public AggregateProcessor(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Batches incoming messages into one message holding the list of their payloads.",
        """
        Configure with:
        - by: 'count' (default) batches any stream by size; 'sequence' joins what a SPLITTER \
        split (one group per sequenceId, complete at sequenceSize, ordered by sequenceNumber); \
        'correlation' joins size messages sharing a correlationId.
        - size: max messages per batch, or per correlation group (positive; not used by \
        'sequence').
        - timeoutMs: flush a partial batch or group after this many milliseconds (optional).
        The remainder is always flushed when the stream completes. A group mode adds complete \
        (and missing positions for 'sequence') to the payload.\
        """,
        Optional.of(new UiDesign("blue", "merge", "Aggregate", 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            BY, false, "count", "How to group messages", "count", "sequence", "correlation"),
        ConfigField.optional(
            SIZE,
            ConfigField.Type.NUMBER,
            null,
            "Most messages per batch or correlation group (positive; not for sequence)"),
        ConfigField.optional(
            TIMEOUT_MS,
            ConfigField.Type.NUMBER,
            null,
            "Flush a partial batch or group after this many ms"));
  }

  @Override
  public String getType() {
    return "AGGREGATE";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> {
      if (mode(config) != By.SEQUENCE) {
        size(config);
      }
    });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return PluginGuard.counted(doProcess(input, config), reporter, "aggregate");
  }

  private Flux<Message<?>> doProcess(
      final Flux<Message<?>> input, final Map<String, Object> config) {
    final By by = mode(config);
    final Duration timeout =
        config.get(TIMEOUT_MS) instanceof Number millis && millis.longValue() > 0
            ? Duration.ofMillis(millis.longValue())
            : null;
    if (by == By.COUNT) {
      final int size = size(config);
      final Flux<List<Message<?>>> batches =
          timeout != null ? input.bufferTimeout(size, timeout) : input.buffer(size);
      return batches.map(AggregateProcessor::merge);
    }
    final int size = by == By.SEQUENCE ? 0 : size(config);
    // ponytail: one open group per key with no cap; add maxOpenGroups if fan-outs grow large.
    // Every group is drained at once (unbounded flatMap), or groupBy would stall on many keys.
    return input
        .groupBy(msg -> groupKey(by, msg, size))
        .flatMap(
            group -> {
              final int expected = group.key().expected();
              Flux<Message<?>> members = group.take(expected);
              if (timeout != null) {
                members = members.take(timeout);
              }
              return members.collectList().map(list -> joined(by, list, expected));
            },
            Integer.MAX_VALUE);
  }

  /** A group and how many members complete it; {@code id} is null for a message without one. */
  private record GroupKey(String id, int expected, Object identity) {}

  /** The group of a message; one without the key is a group of one, under a key of its own. */
  private static GroupKey groupKey(final By by, final Message<?> msg, final int size) {
    final String id = by == By.SEQUENCE ? msg.sequenceId() : msg.correlationId();
    if (id == null) {
      return new GroupKey(null, 1, new Object());
    }
    return new GroupKey(id, by == By.SEQUENCE ? Math.max(1, msg.sequenceSize()) : size, id);
  }

  private static Message<?> merge(final List<Message<?>> batch) {
    return DefaultMessage.create(null, payload(batch, Map.of()))
        .withTraceId(batch.getFirst().traceId())
        .withHeader("sourceIds", batch.stream().map(Message::id).toList())
        .withSourcePort("default");
  }

  /**
   * One group as one message: the envelope of its first member (for a sequence, the parent's, as
   * the SPLITTER copied it), with the sequence fields cleared.
   */
  private static Message<?> joined(final By by, final List<Message<?>> group, final int expected) {
    final List<Message<?>> members =
        by == By.SEQUENCE
            ? group.stream().sorted(Comparator.comparingInt(Message::sequenceNumber)).toList()
            : group;
    final Message<?> first = members.getFirst();
    final Map<String, Object> extra;
    if (by == By.SEQUENCE && first.sequenceId() != null) {
      final List<Integer> seen = members.stream().map(Message::sequenceNumber).toList();
      final List<Integer> missing =
          IntStream.rangeClosed(1, expected).filter(n -> !seen.contains(n)).boxed().toList();
      extra = Map.of("complete", missing.isEmpty(), "missing", missing);
    } else {
      extra = Map.of("complete", members.size() == expected);
    }
    // a joined sequence is its parent again: the SPLITTER put the parent's id in sequenceId
    final boolean parent = by == By.SEQUENCE && first.sequenceId() != null;
    final String id = parent ? first.sequenceId() : UUID.randomUUID().toString();
    return DefaultMessage.from(first, payload(members, extra))
        .withId(id)
        .withCorrelationId(parent ? first.sequenceId() : first.correlationId())
        .withSequence(null, 0, 0)
        .withHeader(SplitterProcessor.PARENT_ID, null)
        .withHeader("sourceIds", members.stream().map(Message::id).toList())
        .withSourcePort("default");
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap") // local, never shared
  private static Map<String, Object> payload(
      final List<Message<?>> batch, final Map<String, Object> extra) {
    final List<Object> items = batch.stream().<Object>map(Message::payload).toList();
    // which node each item came from, in the same order ("" for one the engine did not stamp)
    final List<String> sources =
        batch.stream().map(m -> m.sourceNodeId() == null ? "" : m.sourceNodeId()).toList();
    final Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
    payload.put("items", items);
    payload.put("count", items.size());
    payload.put("sources", sources);
    return payload;
  }

  private static By mode(final Map<String, Object> config) {
    final Object value = config.get(BY);
    if (value == null) {
      return By.COUNT;
    }
    try {
      return By.valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "by must be one of count, sequence, correlation; was '" + value + "'", e);
    }
  }

  private static int size(final Map<String, Object> config) {
    if (config.get(SIZE) instanceof Number size && size.intValue() > 0) {
      return size.intValue();
    }
    throw new IllegalArgumentException("size must be a positive number");
  }
}
