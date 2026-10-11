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

/** Collects messages into batches and emits one message {@code {items:[...], count:n}} each. */
@Component
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class AggregateProcessor implements ProcessorPlugin {

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

  /** Size. */
  private static final String SIZE = "size";

  /** Timeout ms. */
  private static final String TIMEOUT_MS = "timeoutMs";

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Batches incoming messages into one message holding the list of their payloads.",
        "Configure with:\n"
            + "- size: max messages per batch (required, positive).\n"
            + "- timeoutMs: flush a partial batch after this many milliseconds (optional).\n"
            + "The remainder is always flushed when the stream completes.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M5 4l7 8 7-8M12 12v8"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Aggregate</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
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
    return DefaultMessage.create(UUID.randomUUID(), Map.of("items", items, "count", items.size()))
        .withTraceId(batch.getFirst().traceId())
        .withSourcePort("default");
  }

  private static int size(final Map<String, Object> config) {
    if (config.get(SIZE) instanceof Number size && size.intValue() > 0) {
      return size.intValue();
    }
    throw new IllegalArgumentException("size must be a positive number");
  }
}
