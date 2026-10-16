// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.SpelUtils;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
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
  "PMD.GodClass"
})
@NoArgsConstructor
public class ContentFilterProcessor implements ProcessorPlugin {

  /** Type. */
  private static final String TYPE = "CONTENT_FILTER";

  /** Mode include. */
  private static final String MODE_INCLUDE = "INCLUDE";

  /** Mode exclude. */
  private static final String MODE_EXCLUDE = "EXCLUDE";

  /** Config mode. */
  private static final String CONFIG_MODE = "mode";

  /** Config paths. */
  private static final String CONFIG_PATHS = "paths";

  /** Config flatten. */
  private static final String CONFIG_FLATTEN = "flatten";

  /** Config strict. */
  private static final String CONFIG_STRICT = "strictMode";

  /** Config error port. */
  private static final String CONFIG_ERROR_PORT = "errorPort";

  /** Meta pfx. */
  private static final String META_PFX = "metadata.";

  /** Headers pfx. */
  private static final String HEADERS_PFX = "headers.";

  /** Spel meta pfx. */
  private static final String SPEL_META_PFX = "#metadata.";

  /** Spel headers pfx. */
  private static final String SPEL_HEADERS_PFX = "#headers.";

  /** Unchecked. */
  private static final String UNCHECKED = "unchecked";

  /** Payload spel. */
  private static final String PAYLOAD_SPEL = "#payload.";

  /** Err fail. */
  private static final String ERR_FAIL = "Content Filter failed";

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Simplifies a message by removing unimportant, redundant, or sensitive data items.",
        "Configure with:\n"
            + "- mode: 'INCLUDE' (whitelist) or 'EXCLUDE' (blacklist).\n"
            + "- paths: List of SpEL expressions (INCLUDE) or dot-notation paths (EXCLUDE).\n"
            + "- flatten: Boolean. If true, simplifies nested structures into flat maps.\n"
            + "- strictMode: Boolean. If true (default), throws exception if a mandatory path is"
            + " missing.\n"
            + "- errorPort: Optional port to route messages that fail transformation.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-slate-100 rounded-lg flex items-center justify-center text-slate-500">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
                        </svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">C-Filter</div>
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
        ConfigField.choice(
            "mode",
            true,
            null,
            "Keep (INCLUDE) or drop (EXCLUDE) the listed paths",
            "INCLUDE",
            "EXCLUDE"),
        ConfigField.required(
            "paths", ConfigField.Type.LIST, "SpEL expressions (INCLUDE) or dot paths (EXCLUDE)"),
        ConfigField.optional(
            "flatten", ConfigField.Type.BOOLEAN, false, "Flatten nested structures"),
        ConfigField.optional(
            "strictMode", ConfigField.Type.BOOLEAN, true, "Fail when a path is missing"),
        ConfigField.optional("errorPort", ConfigField.Type.STRING, null, "Port for failures"));
  }

  @Override
  public String getType() {
    return TYPE;
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
    return input.flatMap(message -> executeFilter(message, config));
  }

  private Flux<Message<?>> executeFilter(
      final Message<?> message, final Map<String, Object> config) {
    final Object payload = message.payload();
    if (payload instanceof Flux<?> flux) {
      return flux.flatMap(item -> applyFilterToItem(message.withPayload(item), config));
    }
    if (isIterable(payload)) {
      final Iterator<?> iterator = coerceToIterator(payload);
      return Flux.fromIterable(() -> iterator)
          .flatMap(item -> applyFilterToItem(message.withPayload(item), config));
    } else {
      return applyFilterToItem(message, config).flux();
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
    return IntStream.range(0, Array.getLength(items)).mapToObj(i -> Array.get(items, i)).iterator();
  }

  private Mono<Message<?>> applyFilterToItem(
      final Message<?> message, final Map<String, Object> config) {
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
      if (flatten && finalResult.payload() instanceof Map) {
        @SuppressWarnings(UNCHECKED)
        final Map<String, Object> payloadMap = (Map<String, Object>) finalResult.payload();
        finalResult = finalResult.withPayload(MapUtils.flatten(payloadMap));
      }

      return Mono.just(finalResult.withSourcePort("default"));
    } catch (final WorkflowExecutionException e) {
      return handleFailure(message, config, e);
    } catch (final Exception e) {
      return handleFailure(message, config, new WorkflowExecutionException(ERR_FAIL, e));
    }
  }

  private Mono<Message<?>> handleFailure(
      final Message<?> msg,
      final Map<String, Object> config,
      final WorkflowExecutionException err) {
    final String errorPort = (String) config.get(CONFIG_ERROR_PORT);
    final boolean strictMode = (Boolean) config.getOrDefault(CONFIG_STRICT, true);
    if (errorPort != null && !errorPort.isBlank()) {
      return Mono.just(msg.withSourcePort(errorPort).withFailure(null, ERR_FAIL, err.getMessage()));
    }
    if (!strictMode) {
      return Mono.just(msg.withSourcePort("default"));
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
            message.payload() != null ? message.payload() : Map.of(),
            "metadata",
            message.metadata(),
            "headers",
            message.metadata());

    final Object payload = message.payload();
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
    Object val = message.payload();
    for (final String path : paths) {
      if (!isMetadataTargeted(path)) {
        val = SpelUtils.evaluateSync(path.startsWith("#") ? path : "#payload", message, variables);
      }
    }
    return val;
  }

  private void copyCoreHeaders(final Message<?> message, final Map<String, Object> whiteMeta) {
    putIfPresent(whiteMeta, "traceId", message.traceId());
    putIfPresent(whiteMeta, "correlationId", message.correlationId());
    putIfPresent(whiteMeta, "messageId", message.id());
    putIfPresent(whiteMeta, "timestamp", message.timestamp().toEpochMilli());
    putIfPresent(whiteMeta, "sequenceId", message.sequenceId());
    putIfPresent(whiteMeta, "sequenceNumber", message.sequenceNumber());
    putIfPresent(whiteMeta, "sequenceSize", message.sequenceSize());
    putIfPresent(whiteMeta, "replyTo", message.replyTo());
  }

  private static void putIfPresent(
      final Map<String, Object> target, final String key, final Object value) {
    if (value != null) {
      target.put(key, value);
    }
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
        new ConcurrentHashMap<>(MapUtils.asMutableMap(message.payload()));
    final Map<String, Object> metadataMap = new ConcurrentHashMap<>(message.metadata());

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
    if (path.startsWith(META_PFX)) {
      return hasKey(message.metadata(), path.substring(META_PFX.length()));
    }
    if (path.startsWith(HEADERS_PFX)) {
      return hasKey(message.metadata(), path.substring(HEADERS_PFX.length()));
    }
    final String payloadPath =
        path.startsWith(PAYLOAD_SPEL) ? path.substring(PAYLOAD_SPEL.length()) : path;
    if (payloadPath.startsWith("#")) {
      return evaluateExists(message, path);
    }
    final Object payload = message.payload();
    if (payload instanceof Map) {
      @SuppressWarnings(UNCHECKED)
      final Map<String, Object> map = (Map<String, Object>) payload;
      return hasKey(map, payloadPath);
    }
    // Non-map payloads never reach here: includePaths routes them to includeNonMapPayload.
    return false;
  }

  private boolean evaluateExists(final Message<?> message, final String path) {
    try {
      final Map<String, Object> variables =
          Map.of(
              "payload",
              message.payload() != null ? message.payload() : Map.of(),
              "metadata",
              message.metadata(),
              "headers",
              message.metadata());
      return SpelUtils.evaluateSync(path, message, variables) != null;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean hasKey(final Map<String, Object> map, final String path) {
    final String[] parts = path.split("\\.", -1);
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
    return current.containsKey(parts[parts.length - 1]);
  }
}
