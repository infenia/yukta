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

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.UiDesign;
import com.infenia.yukta.plugin.WorkflowExecutionException;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.SpelUtils;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Content Filter simplifies a message by removing unimportant, redundant, or sensitive data items.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidCatchingGenericException",
  "PMD.TooManyMethods",
  "PMD.CyclomaticComplexity",
  "PMD.LawOfDemeter",
  "PMD.GodClass"
})
public class ContentFilterProcessor implements ProcessorPlugin {

  private static final String TYPE = "CONTENT-FILTER";

  private static final String MODE_INCLUDE = "INCLUDE";
  private static final String MODE_EXCLUDE = "EXCLUDE";

  private static final String CONFIG_MODE = "mode";
  private static final String CONFIG_PATHS = "paths";
  private static final String CONFIG_FLATTEN = "flatten";
  private static final String CONFIG_STRICT = "strictMode";
  private static final String CONFIG_ERROR_PORT = "errorPort";

  private static final String META_PFX = "metadata.";
  private static final String HEADERS_PFX = "headers.";
  private static final String SPEL_META_PFX = "#metadata.";
  private static final String SPEL_HEADERS_PFX = "#headers.";

  private static final String UNCHECKED = "unchecked";
  private static final String PAYLOAD_SPEL = "#payload.";
  private static final String ERR_FAIL = "Content Filter failed";

  /** Default constructor. */
  public ContentFilterProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Simplifies a message by removing unimportant, redundant, or sensitive data items.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'INCLUDE' (whitelist) or 'EXCLUDE' (blacklist).\n"
        + "- paths: List of SpEL expressions (INCLUDE) or dot-notation paths (EXCLUDE).\n"
        + "- flatten: Boolean. If true, simplifies nested structures into flat maps.\n"
        + "- strictMode: Boolean. If true (default), throws exception if a mandatory path is"
        + " missing.\n"
        + "- errorPort: Optional port to route messages that fail transformation.";
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
            <div class="flex items-center justify-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl">
              <svg class="w-8 h-8 text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
              </svg>
              <span class="ml-2 font-heading text-xs font-bold text-slate-700 uppercase">C-Filter</span>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final List<String> ports = new ArrayList<>(List.of("default"));
    final String errorPort = (String) config.get(CONFIG_ERROR_PORT);
    if (errorPort != null && !errorPort.isBlank()) {
      ports.add(errorPort);
    }
    return ports;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get(CONFIG_PATHS) == null) {
      return Mono.error(new IllegalArgumentException("paths is mandatory for Content Filter"));
    }
    if (!(config.get(CONFIG_PATHS) instanceof List)) {
      return Mono.error(new IllegalArgumentException("paths must be a List"));
    }
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_INCLUDE);
    if (!MODE_INCLUDE.equalsIgnoreCase(mode) && !MODE_EXCLUDE.equalsIgnoreCase(mode)) {
      return Mono.error(new IllegalArgumentException("mode must be INCLUDE or EXCLUDE"));
    }
    return Mono.empty();
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_INCLUDE);
    if (MODE_INCLUDE.equalsIgnoreCase(mode)) {
      final List<String> paths = (List<String>) config.get(CONFIG_PATHS);
      paths.forEach(SpelUtils::preParse);
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.flatMap(
        message ->
            Flux.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  return executeFilter(message, nodeId, config);
                }));
  }

  private Flux<Message<?>> executeFilter(
      final Message<?> message, final String nodeId, final Map<String, Object> config) {
    final Object payload = message.getPayload();
    if (payload instanceof Flux<?> flux) {
      return flux.flatMap(item -> applyFilterToItem(message.withPayload(item), nodeId, config));
    }
    if (isIterable(payload)) {
      final Iterator<?> iterator = coerceToIterator(payload);
      return Flux.fromIterable(() -> iterator)
          .flatMap(item -> applyFilterToItem(message.withPayload(item), nodeId, config));
    } else {
      return applyFilterToItem(message, nodeId, config).flux();
    }
  }

  private boolean isIterable(final Object payload) {
    return payload instanceof Iterable
        || payload instanceof Stream
        || (payload != null && payload.getClass().isArray());
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
    if (items != null && items.getClass().isArray()) {
      return new ArrayIterator(items, Array.getLength(items));
    }
    return Collections.singletonList(items).iterator();
  }

  private static final class ArrayIterator implements Iterator<Object> {
    private final Object array;
    private final int length;
    private int index;

    /* default */ ArrayIterator(final Object array, final int length) {
      this.array = array;
      this.length = length;
    }

    @Override
    public boolean hasNext() {
      return index < length;
    }

    @Override
    public Object next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      final Object item = Array.get(array, index);
      index++;
      return item;
    }
  }

  private Mono<Message<?>> applyFilterToItem(
      final Message<?> message, final String nodeId, final Map<String, Object> config) {
    try {
      final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_INCLUDE);
      @SuppressWarnings(UNCHECKED)
      final List<String> paths = (List<String>) config.get(CONFIG_PATHS);
      final boolean flatten = (Boolean) config.getOrDefault(CONFIG_FLATTEN, false);
      final boolean strict = (Boolean) config.getOrDefault(CONFIG_STRICT, true);

      final Message<?> result;
      if (MODE_INCLUDE.equalsIgnoreCase(mode)) {
        result = includePaths(message, paths, strict);
      } else {
        result = excludePaths(message, paths);
      }

      Message<?> finalResult = result;
      if (flatten && finalResult.getPayload() instanceof Map) {
        @SuppressWarnings(UNCHECKED)
        final Map<String, Object> payloadMap = (Map<String, Object>) finalResult.getPayload();
        finalResult = finalResult.withPayload(MapUtils.flatten(payloadMap));
      }

      return Mono.just(finalResult.withAddedHistory(nodeId).withSourcePort("default"));
    } catch (final WorkflowExecutionException e) {
      return handleFailure(message, nodeId, config, e);
    } catch (final Exception e) {
      return handleFailure(message, nodeId, config, new WorkflowExecutionException(ERR_FAIL, e));
    }
  }

  private Mono<Message<?>> handleFailure(
      final Message<?> msg,
      final String nodeId,
      final Map<String, Object> config,
      final WorkflowExecutionException err) {
    final String errorPort = (String) config.get(CONFIG_ERROR_PORT);
    final boolean strictMode = (Boolean) config.getOrDefault(CONFIG_STRICT, true);
    if (errorPort != null && !errorPort.isBlank()) {
      return Mono.just(
          msg.withSourcePort(errorPort)
              .withAddedHistory(nodeId)
              .withFailure(null, ERR_FAIL, err.getMessage()));
    }
    if (!strictMode) {
      return Mono.just(msg.withAddedHistory(nodeId).withSourcePort("default"));
    }
    return Mono.error(err);
  }

  private Message<?> includePaths(
      final Message<?> message, final List<String> paths, final boolean strictMode) {
    final boolean metadataTargeted = paths.stream().anyMatch(this::isMetadataTargeted);
    final Map<String, Object> whiteMeta = new ConcurrentHashMap<>();
    if (metadataTargeted) {
      copyCoreHeaders(message, whiteMeta);
    }

    final Map<String, Object> variables =
        Map.of(
            "payload",
            message.getPayload() != null ? message.getPayload() : Map.of(),
            "metadata",
            message.getMetadata());

    final Object payload = message.getPayload();
    final Object resultPayload;

    if (payload instanceof Map || payload == null) {
      resultPayload = includeMapPayload(message, paths, strictMode, whiteMeta, variables);
    } else {
      resultPayload = includeNonMapPayload(message, paths, variables);
    }

    Message<?> result = message.withPayload(resultPayload);
    if (metadataTargeted) {
      result = result.withMetadata(whiteMeta);
    }
    return result;
  }

  private Object includeMapPayload(
      final Message<?> message,
      final List<String> paths,
      final boolean strictMode,
      final Map<String, Object> whiteMeta,
      final Map<String, Object> variables) {
    final Map<String, Object> filteredPayload = new ConcurrentHashMap<>();
    for (final String path : paths) {
      if (strictMode && !pathExists(message, path)) {
        throw new WorkflowExecutionException("Mandatory path missing: " + path);
      }

      final boolean isMetadata = isMetadataTargeted(path);
      final String effectiveSpel = getEffectiveSpel(path);
      final Object value = SpelUtils.evaluateSync(effectiveSpel, message, variables);

      if (isMetadata) {
        MapUtils.setNestedValue(whiteMeta, getCleanPath(path), value);
      } else {
        MapUtils.setNestedValue(filteredPayload, getCleanPayloadPath(path), value);
      }
    }
    return filteredPayload;
  }

  private Object includeNonMapPayload(
      final Message<?> message, final List<String> paths, final Map<String, Object> variables) {
    Object val = message.getPayload();
    for (final String path : paths) {
      if (!isMetadataTargeted(path)) {
        val = SpelUtils.evaluateSync(path.startsWith("#") ? path : "#payload", message, variables);
      }
    }
    return val;
  }

  private void copyCoreHeaders(final Message<?> message, final Map<String, Object> whiteMeta) {
    final List<String> coreHeaders =
        List.of(
            "traceId",
            "correlationId",
            "messageId",
            "timestamp",
            "sequenceId",
            "sequenceNumber",
            "sequenceSize",
            "replyTo");
    coreHeaders.forEach(
        h -> {
          final Object val = getCoreHeader(message, h);
          if (val != null) {
            whiteMeta.put(h, val);
          }
        });
  }

  private String getEffectiveSpel(final String path) {
    if (path.startsWith(META_PFX)) {
      return SPEL_META_PFX + path.substring(META_PFX.length());
    }
    if (path.startsWith(HEADERS_PFX)) {
      return SPEL_META_PFX + path.substring(HEADERS_PFX.length());
    }
    if (path.startsWith(SPEL_META_PFX) || path.startsWith(SPEL_HEADERS_PFX)) {
      return path;
    }
    return path.startsWith("#") ? path : PAYLOAD_SPEL + path;
  }

  private String getCleanPath(final String path) {
    if (path.startsWith(META_PFX)) {
      return path.substring(META_PFX.length());
    }
    if (path.startsWith(HEADERS_PFX)) {
      return path.substring(HEADERS_PFX.length());
    }
    if (path.startsWith(SPEL_META_PFX)) {
      return path.substring(SPEL_META_PFX.length());
    }
    if (path.startsWith(SPEL_HEADERS_PFX)) {
      return path.substring(SPEL_HEADERS_PFX.length());
    }
    return path;
  }

  private String getCleanPayloadPath(final String path) {
    String clean = getCleanPath(path);
    if (clean.startsWith(PAYLOAD_SPEL)) {
      clean = clean.substring(PAYLOAD_SPEL.length());
    }
    return clean;
  }

  private boolean isMetadataTargeted(final String path) {
    return path.startsWith(META_PFX)
        || path.startsWith(HEADERS_PFX)
        || path.startsWith(SPEL_META_PFX)
        || path.startsWith(SPEL_HEADERS_PFX);
  }

  private Message<?> excludePaths(final Message<?> message, final List<String> paths) {
    final Map<String, Object> payloadMap =
        new ConcurrentHashMap<>(MapUtils.asMutableMap(message.getPayload()));
    final Map<String, Object> metadataMap = new ConcurrentHashMap<>(message.getMetadata());

    for (final String path : paths) {
      if (path.startsWith(META_PFX)) {
        MapUtils.removeNestedValue(metadataMap, path.substring(META_PFX.length()));
      } else if (path.startsWith(HEADERS_PFX)) {
        MapUtils.removeNestedValue(metadataMap, path.substring(HEADERS_PFX.length()));
      } else if (path.startsWith(SPEL_META_PFX)) {
        MapUtils.removeNestedValue(metadataMap, path.substring(SPEL_META_PFX.length()));
      } else if (path.startsWith(SPEL_HEADERS_PFX)) {
        MapUtils.removeNestedValue(metadataMap, path.substring(SPEL_HEADERS_PFX.length()));
      } else {
        String cleanPath = path;
        if (cleanPath.startsWith(PAYLOAD_SPEL)) {
          cleanPath = cleanPath.substring(PAYLOAD_SPEL.length());
        }
        MapUtils.removeNestedValue(payloadMap, cleanPath);
      }
    }

    return message.withPayload(payloadMap).withMetadata(metadataMap);
  }

  private boolean pathExists(final Message<?> message, final String path) {
    if (!path.startsWith("#") && !path.startsWith(META_PFX) && !path.startsWith(HEADERS_PFX)) {
      final Object payload = message.getPayload();
      if (payload instanceof Map) {
        @SuppressWarnings(UNCHECKED)
        final Map<String, Object> map = (Map<String, Object>) payload;
        return hasKey(map, path);
      }
      return payload != null;
    }

    if (path.startsWith(META_PFX) || path.startsWith(HEADERS_PFX)) {
      final String cleanPath =
          path.startsWith(META_PFX)
              ? path.substring(META_PFX.length())
              : path.substring(HEADERS_PFX.length());
      return hasKey(message.getMetadata(), cleanPath);
    }

    String payloadPath = path;
    if (payloadPath.startsWith(PAYLOAD_SPEL)) {
      payloadPath = payloadPath.substring(PAYLOAD_SPEL.length());
    }
    if (!payloadPath.startsWith("#")) {
      final Object payload = message.getPayload();
      if (payload instanceof Map) {
        @SuppressWarnings(UNCHECKED)
        final Map<String, Object> map = (Map<String, Object>) payload;
        return hasKey(map, payloadPath);
      }
      return payload != null;
    }

    return evaluateExists(message, path);
  }

  private boolean evaluateExists(final Message<?> message, final String path) {
    try {
      final Map<String, Object> variables =
          Map.of(
              "payload",
              message.getPayload() != null ? message.getPayload() : Map.of(),
              "metadata",
              message.getMetadata());
      final String effectiveSpel = path.startsWith("#") ? path : PAYLOAD_SPEL + path;
      return SpelUtils.evaluateSync(effectiveSpel, message, variables) != null;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean hasKey(final Map<String, Object> map, final String path) {
    final String[] parts = path.split("\\.");
    Map<String, Object> current = map;
    for (int i = 0; i < parts.length - 1; i++) {
      final Object next = current.get(parts[i]);
      if (next instanceof Map) {
        @SuppressWarnings(UNCHECKED)
        final Map<String, Object> nextMap = (Map<String, Object>) next;
        current = nextMap;
      } else {
        return false;
      }
    }
    return current != null && current.containsKey(parts[parts.length - 1]);
  }

  private Object getCoreHeader(final Message<?> message, final String header) {
    return switch (header) {
      case "traceId" -> message.getTraceId();
      case "correlationId" -> message.getCorrelationId();
      case "messageId" -> message.getMessageId();
      case "timestamp" -> message.getTimestamp();
      case "sequenceId" -> message.getSequenceId();
      case "sequenceNumber" -> message.getSequenceNumber();
      case "sequenceSize" -> message.getSequenceSize();
      case "replyTo" -> message.getReplyTo();
      default -> message.getMetadata().get(header);
    };
  }
}
