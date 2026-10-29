// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.script;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.transformer.ScriptEngine;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.springframework.stereotype.Component;

/** GraalJS-backed {@link ScriptEngine}. */
@Component
@Slf4j
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class GraalJsScriptEngine implements ScriptEngine {

  /** Polyglot language id. */
  private static final String LANG = "js";

  /** Classes a script may load with {@code Java.type}, besides {@code java.time.*}. */
  private static final Set<String> SAFE_CLASSES =
      Set.of(
          "java.lang.Math",
          "java.lang.String",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Double",
          "java.lang.Boolean",
          "java.util.UUID",
          "java.util.Base64",
          "java.util.ArrayList",
          "java.util.HashMap",
          "java.util.List",
          "java.util.Map");

  /** Most values (map entries, list elements and scalars) a script may be given or return. */
  private static final int MAX_VALUES = 1_000_000;

  /** Deepest nesting of maps and lists a script may be given or return. */
  private static final int MAX_DEPTH = 64;

  /** Longest string a script may build, in characters (GraalJS {@code js.string-length-limit}). */
  private static final String STRING_LIMIT = "10000000";

  /**
   * What a script may do with host objects: call public members of the value classes it may load
   * with {@code Java.type}, and index lists and maps. It may not implement host interfaces, and
   * never sees a {@code Class}. The data it is given are not host objects at all.
   */
  private static final HostAccess HOST_ACCESS =
      HostAccess.newBuilder()
          .allowPublicAccess(true)
          .allowListAccess(true)
          .allowArrayAccess(true)
          .allowMapAccess(true)
          .allowIterableAccess(true)
          .allowIteratorAccess(true)
          .allowAccessInheritance(true)
          .denyAccess(Class.class)
          .build();

  /** The GraalVM option that caps heap use; only Oracle GraalVM has it. */
  private static final String HEAP_OPTION = "sandbox.MaxHeapMemory";

  /** Shared engine so compiled code is reused across contexts. */
  private static final Engine JS_ENGINE =
      newEngine(Long.getLong("yukta.script.max-heap-bytes", 0L), HEAP_OPTION);

  /** Cancels scripts that outlive {@link #timeoutMs}. */
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "js-script-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  /** Most scripts kept parsed; the least recently used are dropped beyond this many. */
  private static final long SOURCE_CACHE_MAX = 1_000;

  /** Parsed sources keyed by script text. A dropped script is parsed again when it is next run. */
  private final Cache<String, Source> sourceCache =
      Caffeine.newBuilder().maximumSize(SOURCE_CACHE_MAX).executor(Runnable::run).build();

  /** Longest a script may run, in milliseconds ({@code -Dyukta.script.timeout-ms}). */
  private final long timeoutMs = Long.getLong("yukta.script.timeout-ms", 10_000L);

  /** Most statements a script may run ({@code -Dyukta.script.max-statements}). */
  private final long maxStatements = Long.getLong("yukta.script.max-statements", 50_000_000L);

  /** Default constructor. */
  public GraalJsScriptEngine() {
    super();
  }

  /**
   * Builds the shared engine. {@code maxHeap}, from {@code -Dyukta.script.max-heap-bytes} (0 = no
   * limit, the default), sets {@code sandbox.MaxHeapMemory}, which only Oracle GraalVM has;
   * elsewhere it is logged and left off.
   *
   * @param maxHeap the heap limit in bytes, or 0 for none
   * @param heapOption the option that carries the limit
   * @return the engine
   */
  /* default */ static Engine newEngine(final long maxHeap, final String heapOption) {
    if (maxHeap > 0) {
      try {
        // ponytail: the limit covers the whole shared engine, not each script; per-context
        // engines if one script must not eat the budget of the others
        return plainEngine().option(heapOption, maxHeap + "B").build();
      } catch (IllegalArgumentException | IllegalStateException e) {
        log.atWarn()
            .setMessage(
                "yukta.script.max-heap-bytes ignored, this runtime has no script heap limit")
            .setCause(e)
            .log();
      }
    }
    // a fresh builder: the rejected option would still be on the one above
    return plainEngine().build();
  }

  private static Engine.Builder plainEngine() {
    return Engine.newBuilder().option("engine.WarnInterpreterOnly", "false");
  }

  @Override
  public void precompile(final String script) {
    source(script);
  }

  @Override
  public Object eval(final String script, final Message<?> message) {
    try (Context context =
        // nosem: yukta-graalvm-allow-host-class-lookup (only the harmless classes listed above)
        Context.newBuilder(LANG)
            .engine(JS_ENGINE)
            .allowHostAccess(HOST_ACCESS)
            .allowHostClassLookup(GraalJsScriptEngine::isSafeClass)
            // the only memory guard GraalVM Community offers; it is an experimental option, so
            // the context has to allow experimental options (none other is set)
            .allowExperimentalOptions(true)
            .option("js.string-length-limit", STRING_LIMIT)
            .resourceLimits(ResourceLimits.newBuilder().statementLimit(maxStatements, null).build())
            .build()) {
      final Value bindings = context.getBindings(LANG);
      final DataCopier data = new DataCopier();
      final Object payload = data.copy(message.payload(), 0);
      final Object metadata = data.copy(message.metadata(), 0);
      bindings.putMember(
          "message", ProxyObject.fromMap(messageData(message, payload, metadata, data)));
      bindings.putMember("payload", payload);
      bindings.putMember("metadata", metadata);
      // a script that never ends is cancelled; closing the context is the only way to stop it
      final ScheduledFuture<?> watchdog =
          WATCHDOG.schedule(() -> context.close(true), timeoutMs, TimeUnit.MILLISECONDS);
      try {
        return new ResultCopier().detach(context.eval(source(script)), 0);
      } catch (PolyglotException e) {
        if (e.isResourceExhausted()) {
          throw new IllegalStateException(
              "Script stopped: over " + maxStatements + " statements or the heap limit", e);
        }
        if (e.isCancelled()) {
          throw new IllegalStateException("Script cancelled after " + timeoutMs + " ms", e);
        }
        throw e;
      } finally {
        watchdog.cancel(false);
      }
    }
  }

  /** Scripts may use value classes, never ones that reach the process, files or the network. */
  private static boolean isSafeClass(final String name) {
    return name.startsWith("java.time.") || SAFE_CLASSES.contains(name);
  }

  private Source source(final String script) {
    return sourceCache.get(script, s -> Source.newBuilder(LANG, s, "mapper.js").buildLiteral());
  }

  /**
   * Number of scripts kept parsed.
   *
   * @return the cache size
   */
  /* default */ long sourceCacheSize() {
    sourceCache.cleanUp();
    return sourceCache.estimatedSize();
  }

  /** The message as plain data: its fields, with no methods to call. */
  private static Map<String, Object> messageData(
      final Message<?> message,
      final Object payload,
      final Object metadata,
      final DataCopier data) {
    final Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", message.id());
    fields.put("correlationId", message.correlationId());
    fields.put("replyTo", message.replyTo());
    fields.put("traceId", message.traceId());
    fields.put("timestamp", String.valueOf(message.timestamp()));
    fields.put("expiration", message.expiration());
    fields.put("formatIndicator", message.formatIndicator());
    fields.put("sourcePort", message.sourcePort());
    fields.put("sourceNodeId", message.sourceNodeId());
    fields.put("workflowId", message.workflowId());
    fields.put("messageHistory", data.copy(message.messageHistory(), 1));
    fields.put("sequenceId", message.sequenceId());
    fields.put("sequenceNumber", message.sequenceNumber());
    fields.put("sequenceSize", message.sequenceSize());
    fields.put("priority", message.priority());
    fields.put("controlMessage", message.controlMessage());
    fields.put("origDest", message.origDest());
    fields.put("failureReason", message.failureReason());
    fields.put("exceptionDetail", message.exceptionDetail());
    fields.put("retryCount", message.retryCount());
    fields.put("metadata", metadata);
    fields.put("payload", payload);
    return fields;
  }

  /**
   * Copies what a script is given into polyglot proxies, so it sees plain data only: not the live
   * objects (which it could change, or call methods on) and not host objects of any kind. Values
   * that are not text, numbers, booleans, maps or lists are given as their text.
   */
  private static final class DataCopier {

    /** How many values were copied so far. */
    private int values;

    /* default */ Object copy(final Object value, final int depth) {
      countValue(depth);
      if (isScalar(value)) {
        return value;
      }
      if (value instanceof Map<?, ?> map) {
        return copyMap(map, depth);
      }
      if (value instanceof Iterable<?> items) {
        return copyElements(items, depth);
      }
      if (value instanceof Number number) {
        return number.doubleValue();
      }
      return value.getClass().isArray() ? copyArray(value, depth) : String.valueOf(value);
    }

    private void countValue(final int depth) {
      values++;
      if (values > MAX_VALUES) {
        throw new IllegalArgumentException(
            "Script input too large (more than " + MAX_VALUES + " values)");
      }
      if (depth > MAX_DEPTH) {
        throw new IllegalArgumentException(
            "Script input nested too deeply (more than " + MAX_DEPTH + " levels)");
      }
    }

    private static boolean isScalar(final Object value) {
      return value == null
          || value instanceof String
          || value instanceof Boolean
          || value instanceof Integer
          || value instanceof Long
          || value instanceof Double;
    }

    private Object copyMap(final Map<?, ?> map, final int depth) {
      final Map<String, Object> entries = new LinkedHashMap<>();
      map.forEach((key, entry) -> entries.put(String.valueOf(key), copy(entry, depth + 1)));
      return ProxyObject.fromMap(entries);
    }

    private Object copyElements(final Iterable<?> items, final int depth) {
      final List<Object> elements = new ArrayList<>();
      items.forEach(item -> elements.add(copy(item, depth + 1)));
      return ProxyArray.fromList(elements);
    }

    private Object copyArray(final Object array, final int depth) {
      final int length = Array.getLength(array);
      final List<Object> elements = new ArrayList<>(Math.min(length, 1_024));
      for (int i = 0; i < length; i++) {
        elements.add(copy(Array.get(array, i), depth + 1));
      }
      return ProxyArray.fromList(elements);
    }
  }

  /** Copies the result of a script into plain Java values, within the same size limits. */
  private static final class ResultCopier {

    /** How many values were copied so far. */
    private int values;

    /* default */ Object detach(final Value value, final int depth) {
      values++;
      if (values > MAX_VALUES) {
        throw new IllegalStateException(
            "Script result too large (more than " + MAX_VALUES + " values)");
      }
      if (depth > MAX_DEPTH) {
        throw new IllegalStateException(
            "Script result nested too deeply (more than " + MAX_DEPTH + " levels)");
      }
      if (value.isNull()) {
        return null;
      }
      if (value.isNumber()) {
        return value.as(Number.class);
      }
      if (value.isString()) {
        return value.asString();
      }
      if (value.isBoolean()) {
        return value.asBoolean();
      }
      return detachContainer(value, depth);
    }

    private Object detachContainer(final Value value, final int depth) {
      if (value.hasArrayElements()) {
        final List<Object> list = new ArrayList<>();
        for (long i = 0; i < value.getArraySize(); i++) {
          list.add(detach(value.getArrayElement(i), depth + 1));
        }
        return list;
      }
      if (value.hasMembers()) {
        final Map<String, Object> map = new HashMap<>();
        for (final String key : value.getMemberKeys()) {
          map.put(key, detach(value.getMember(key), depth + 1));
        }
        return map;
      }
      return value.as(Object.class);
    }
  }
}
