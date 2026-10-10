// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collects messages into batches and emits one message {@code {items:[...], count:n}} each. */
@Component
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
@NoArgsConstructor
public final class AggregateProcessor implements ProcessorPlugin {

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
        Optional.empty());
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
