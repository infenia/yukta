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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Collects messages into batches and emits one message {@code {items:[...], count:n,
 * sources:[...]}} each, where {@code sources} names the node each item came from.
 */
@Component
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class AggregateProcessor implements ProcessorPlugin {

  /** Size. */
  private static final String SIZE = "size";

  /** Timeout ms. */
  private static final String TIMEOUT_MS = "timeoutMs";

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
        "Configure with:\n"
            + "- size: max messages per batch (required, positive).\n"
            + "- timeoutMs: flush a partial batch after this many milliseconds (optional).\n"
            + "The remainder is always flushed when the stream completes.",
        Optional.of(new UiDesign("blue", "merge", "Aggregate", 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required("size", ConfigField.Type.NUMBER, "Most messages per batch (positive)"),
        ConfigField.optional(
            "timeoutMs",
            ConfigField.Type.NUMBER,
            null,
            "Flush a partial batch after this many ms"));
  }

  @Override
  public String getType() {
    return "AGGREGATE";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> size(config));
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return PluginGuard.counted(doProcess(input, config), reporter, "aggregate");
  }

  private Flux<Message<?>> doProcess(
      final Flux<Message<?>> input, final Map<String, Object> config) {
    final int size = size(config);
    final Flux<List<Message<?>>> batches =
        config.get(TIMEOUT_MS) instanceof Number millis && millis.longValue() > 0
            ? input.bufferTimeout(size, Duration.ofMillis(millis.longValue()))
            : input.buffer(size);
    return batches.map(AggregateProcessor::merge);
  }

  private static Message<?> merge(final List<Message<?>> batch) {
    final List<Object> items = batch.stream().<Object>map(Message::payload).toList();
    // which node each item came from, in the same order ("" for one the engine did not stamp)
    final List<String> sources =
        batch.stream().map(m -> m.sourceNodeId() == null ? "" : m.sourceNodeId()).toList();
    return DefaultMessage.create(
            UUID.randomUUID(), Map.of("items", items, "count", items.size(), "sources", sources))
        .withTraceId(batch.getFirst().traceId())
        .withHeader("sourceIds", batch.stream().map(Message::id).toList())
        .withSourcePort("default");
  }

  private static int size(final Map<String, Object> config) {
    if (config.get(SIZE) instanceof Number size && size.intValue() > 0) {
      return size.intValue();
    }
    throw new IllegalArgumentException("size must be a positive number");
  }
}
