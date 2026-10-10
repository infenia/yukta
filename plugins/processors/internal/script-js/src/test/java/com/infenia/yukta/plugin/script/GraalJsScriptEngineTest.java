// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.script;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.UseConcurrentHashMap"})
class GraalJsScriptEngineTest {

  private final GraalJsScriptEngine engine = new GraalJsScriptEngine();

  private Object run(final String script) {
    return engine.eval(script, DefaultMessage.create(UUID.randomUUID(), Map.of("n", 2)));
  }

  @Test
  void detachesObjectsArraysAndScalars() {
    final String script = "({double: payload.n * 2, tags: ['a', 'b'], ok: true, s: 'x', z: null})";

    engine.precompile(script);

    final Map<String, Object> expected = new java.util.LinkedHashMap<>();
    expected.put("double", 4);
    expected.put("tags", List.of("a", "b"));
    expected.put("ok", true);
    expected.put("s", "x");
    expected.put("z", null);
    assertThat(run(script)).isEqualTo(expected);
  }

  @Test
  void returnsScalarsDirectly() {
    assertThat(run("payload.n + 1")).isEqualTo(3);
    assertThat(run("'hi'")).isEqualTo("hi");
    assertThat(run("false")).isEqualTo(false);
    assertThat(run("null")).isNull();
  }

  @Test
  void fallsBackToHostObjectForOpaqueValues() {
    assertThat(run("Symbol('x')")).isNotNull();
    assertThat(run("Java.type('java.lang.Math').abs(-3)")).isEqualTo(3);
  }
}
