// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.script;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.transformer.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

/** GraalJS-backed {@link ScriptEngine}. */
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
public class GraalJsScriptEngine implements ScriptEngine {

  /** Polyglot language id. */
  private static final String LANG = "js";

  /** Shared engine so compiled code is reused across contexts. */
  private static final Engine JS_ENGINE =
      Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();

  /** Parsed sources keyed by script text. */
  private final Map<String, Source> sourceCache = new ConcurrentHashMap<>();

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
        // nosem: yukta-graalvm-allow-host-class-lookup (trusted workflow scripts)
        Context.newBuilder(LANG)
            .engine(JS_ENGINE)
            // nosem: yukta-graalvm-host-access-all (trusted workflow scripts)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(s -> true)
            .build()) {
      final Value bindings = context.getBindings(LANG);
      bindings.putMember("message", message);
      bindings.putMember("payload", message.getPayload());
      bindings.putMember("metadata", message.getMetadata());
      return detach(context.eval(source(script)));
    }
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
