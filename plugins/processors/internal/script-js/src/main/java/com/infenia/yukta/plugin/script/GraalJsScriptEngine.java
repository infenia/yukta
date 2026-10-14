// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.script;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.transformer.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

/** GraalJS-backed {@link ScriptEngine}. */
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
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

  /** Shared engine so compiled code is reused across contexts. */
  private static final Engine JS_ENGINE =
      Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();

  /** Cancels scripts that outlive {@link #timeoutMs}. */
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "js-script-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  /** Parsed sources keyed by script text. */
  private final Map<String, Source> sourceCache = new ConcurrentHashMap<>();

  /** Longest a script may run, in milliseconds ({@code -Dyukta.script.timeout-ms}). */
  private final long timeoutMs = Long.getLong("yukta.script.timeout-ms", 10_000L);

  /** Default constructor. */
  public GraalJsScriptEngine() {
    super();
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
            // nosem: yukta-graalvm-host-access-all (no reflection: Class objects are denied)
            .allowHostAccess(HostAccess.newBuilder(HostAccess.ALL).denyAccess(Class.class).build())
            .allowHostClassLookup(GraalJsScriptEngine::isSafeClass)
            .build()) {
      final Value bindings = context.getBindings(LANG);
      bindings.putMember("message", message);
      bindings.putMember("payload", message.payload());
      bindings.putMember("metadata", message.metadata());
      // a script that never ends is cancelled; closing the context is the only way to stop it
      final ScheduledFuture<?> watchdog =
          WATCHDOG.schedule(() -> context.close(true), timeoutMs, TimeUnit.MILLISECONDS);
      try {
        return detach(context.eval(source(script)));
      } catch (PolyglotException e) {
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
    return sourceCache.computeIfAbsent(
        script, s -> Source.newBuilder(LANG, s, "mapper.js").buildLiteral());
  }

  private static Object detach(final Value value) {
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
    return detachContainer(value);
  }

  private static Object detachContainer(final Value value) {
    if (value.hasArrayElements()) {
      final List<Object> list = new ArrayList<>((int) value.getArraySize());
      for (int i = 0; i < value.getArraySize(); i++) {
        list.add(detach(value.getArrayElement(i)));
      }
      return list;
    }
    if (value.hasMembers()) {
      final Map<String, Object> map = new HashMap<>();
      for (final String key : value.getMemberKeys()) {
        map.put(key, detach(value.getMember(key)));
      }
      return map;
    }
    return value.as(Object.class);
  }
}
