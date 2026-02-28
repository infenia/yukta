/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.UiDesign;
import com.infenia.yukta.plugin.WorkflowExecutionException;
import com.infenia.yukta.util.SpelUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Splitter breaks a single composite message into individual messages. */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidCatchingGenericException",
  "PMD.TooManyMethods",
  "PMD.ExceptionAsFlowControl",
  "PMD.CyclomaticComplexity"
})
public class SplitterProcessor implements ProcessorPlugin {

  private static final String TYPE = "SPLITTER";
  private static final String CFG_ITEMS = "itemsPath";
  private static final String CFG_MAPPING = "headerMapping";
  private static final String CFG_PARALLEL = "parallel";
  private static final String CFG_CONCURRENCY = "concurrency";
  private static final String CFG_STRICT = "strictMode";
  private static final String CFG_ERROR_PORT = "errorPort";

  private static final int DEF_CONCURRENCY = 1024;
  private static final String ERR_FAIL = "Splitter failed";

  /** Default constructor. */
  public SplitterProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Splits composite message into individual items.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure itemsPath, parallel, concurrency.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1 relative">
                <svg class="w-8 h-8 text-indigo-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h8M8 12h8m-8 5h8M4 4h16v16H4z"/>
                </svg>
                <div class="text-[10px] text-slate-500 font-medium uppercase">Splitter</div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final List<String> ports = new ArrayList<>(List.of("default"));
    final String errorPort = (String) config.get(CFG_ERROR_PORT);
    if (errorPort != null && !errorPort.isBlank()) {
      ports.add(errorPort);
    }
    return ports;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get(CFG_ITEMS) == null) {
      return Mono.error(new IllegalArgumentException("itemsPath mandatory"));
    }
    return Mono.empty();
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    SpelUtils.preParse((String) config.get(CFG_ITEMS));
    final Object mapping = config.get(CFG_MAPPING);
    if (mapping instanceof Map<?, ?> map) {
      map.values().stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .forEach(SpelUtils::preParse);
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String path = (String) config.get(CFG_ITEMS);
    final boolean parallel = (Boolean) config.getOrDefault(CFG_PARALLEL, true);
    final int concurrency =
        ((Number) config.getOrDefault(CFG_CONCURRENCY, DEF_CONCURRENCY)).intValue();
    if (parallel) {
      return input.flatMap(msg -> split(msg, path, config), concurrency);
    }
    return input.concatMap(msg -> split(msg, path, config));
  }

  private Flux<Message<?>> split(
      final Message<?> parent, final String path, final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          final boolean strict = (Boolean) config.getOrDefault(CFG_STRICT, true);
          final String errorPort = (String) config.get(CFG_ERROR_PORT);
          try {
            final Map<String, Object> variables =
                Map.of("payload", parent.getPayload(), "metadata", parent.getMetadata());
            final Object items = SpelUtils.evaluateSync(path, parent, variables);
            if (items == null) {
              return handleNull(parent, nodeId, errorPort, strict);
            }

            final Iterator<?> iterator = coerceToIterator(items);
            final int totalSize =
                (items instanceof Collection) ? ((Collection<?>) items).size() : 0;

            return Flux.fromIterable(() -> iterator)
                .buffer(2, 1)
                .index()
                .map(
                    tuple -> {
                      final long idx = tuple.getT1() + 1;
                      final List<?> window = tuple.getT2();
                      final Object item = window.get(0);
                      final boolean isLast = window.size() < 2;
                      final int total = isLast ? (int) idx : totalSize;
                      return createChild(parent, item, (int) idx, total, config, nodeId, variables);
                    });
          } catch (final Exception e) {
            return handleError(parent, nodeId, errorPort, strict, e);
          }
        });
  }

  private Flux<Message<?>> handleNull(
      final Message<?> parent, final String nodeId, final String errorPort, final boolean strict) {
    if (strict) {
      return handleError(
          parent, nodeId, errorPort, true, new WorkflowExecutionException("itemsPath null"));
    }
    return Flux.empty();
  }

  private Iterator<?> coerceToIterator(final Object items) {
    if (items instanceof Iterable) {
      return ((Iterable<?>) items).iterator();
    }
    if (items instanceof Stream) {
      return ((Stream<?>) items).iterator();
    }
    if (items instanceof Iterator) {
      return (Iterator<?>) items;
    }
    if (items.getClass().isArray()) {
      return Arrays.asList((Object[]) items).iterator();
    }
    return Collections.singletonList(items).iterator();
  }

  private Message<?> createChild(
      final Message<?> parent,
      final Object item,
      final int index,
      final int total,
      final Map<String, Object> config,
      final String nodeId,
      final Map<String, Object> variables) {
    Message<?> child =
        DefaultMessage.create(null, item)
            .withTraceId(parent.getTraceId())
            .withPriority(parent.getPriority())
            .withCorrelationId(parent.getMessageId())
            .withSequence(parent.getMessageId(), index, total)
            .withMetadata(parent.getMetadata())
            .withReplyTo(parent.getReplyTo())
            .withTimestamp(Instant.ofEpochMilli(parent.getTimestamp()));
    if (parent.getExpiration() > 0) {
      child = child.withExpiration(parent.getExpiration());
    }
    if (parent.getFormatIndicator() != null) {
      child = child.withFormatIndicator(parent.getFormatIndicator());
    }
    @SuppressWarnings("unchecked")
    final Map<String, String> mapping = (Map<String, String>) config.get(CFG_MAPPING);
    if (mapping != null) {
      child = applyMappings(parent, child, mapping, config, variables);
    }
    return child.withAddedHistory(nodeId).withSourcePort("default");
  }

  private Message<?> applyMappings(
      final Message<?> parent,
      final Message<?> child,
      final Map<String, String> mapping,
      final Map<String, Object> config,
      final Map<String, Object> variables) {
    Message<?> current = child;
    final boolean strict = (Boolean) config.getOrDefault(CFG_STRICT, true);
    for (final Map.Entry<String, String> entry : mapping.entrySet()) {
      current = doApplyMapping(parent, current, entry, variables, strict);
    }
    return current;
  }

  private Message<?> doApplyMapping(
      final Message<?> parentMessage,
      final Message<?> childMessage,
      final Map.Entry<String, String> entry,
      final Map<String, Object> vars,
      final boolean strict) {
    try {
      final Object value = SpelUtils.evaluateSync(entry.getValue(), parentMessage, vars);
      if (value == null && strict) {
        throw new WorkflowExecutionException("Mapping null");
      }
      if (value != null) {
        return childMessage.withHeader(entry.getKey(), value);
      }
    } catch (final WorkflowExecutionException e) {
      if (strict) {
        throw e;
      }
    } catch (final Exception e) {
      if (strict) {
        throw new WorkflowExecutionException("Mapping failed", e);
      }
    }
    return childMessage;
  }

  private Flux<Message<?>> handleError(
      final Message<?> parent,
      final String nodeId,
      final String errorPort,
      final boolean strict,
      final Throwable error) {
    if (log.isErrorEnabled()) {
      log.error("Splitter failed: {}", error.getMessage());
    }
    if (errorPort != null && !errorPort.isBlank()) {
      return Flux.just(
          parent
              .withSourcePort(errorPort)
              .withAddedHistory(nodeId)
              .withFailure(null, ERR_FAIL, error.getMessage()));
    }
    if (!strict) {
      return Flux.empty();
    }
    if (error instanceof WorkflowExecutionException) {
      return Flux.error(error);
    }
    return Flux.error(new WorkflowExecutionException(ERR_FAIL, error));
  }
}
