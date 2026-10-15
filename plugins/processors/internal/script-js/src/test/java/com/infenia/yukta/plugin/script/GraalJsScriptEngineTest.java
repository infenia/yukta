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
  void sourceCacheIsBoundedAndDroppedScriptStillRuns() {
    for (int i = 0; i < 1_200; i++) {
      engine.precompile("payload.n + " + i);
    }

    assertThat(engine.sourceCacheSize()).isLessThanOrEqualTo(1_000L);
    assertThat(run("payload.n + 0")).isEqualTo(2);
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
    assertThat(run("Java.type('java.time.Duration').ofSeconds(2).getSeconds()")).isEqualTo(2L);
  }

  @Test
  void scriptsCannotReachTheProcessOrReflection() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> run("Java.type('java.lang.Runtime').getRuntime().exec('true')"))
        .isInstanceOf(RuntimeException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> run("Java.type('java.lang.ProcessBuilder')"))
        .isInstanceOf(RuntimeException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> run("payload.getClass().forName('java.lang.Runtime')"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void scriptThatNeverEndsIsCancelled() {
    System.setProperty("yukta.script.timeout-ms", "300");
    try {
      final GraalJsScriptEngine limited = new GraalJsScriptEngine();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  limited.eval(
                      "while (true) {}", DefaultMessage.create(UUID.randomUUID(), Map.of())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cancelled");
    } finally {
      System.clearProperty("yukta.script.timeout-ms");
    }
  }

  @Test
  void scriptThatRunsTooManyStatementsIsStopped() {
    System.setProperty("yukta.script.max-statements", "10000");
    try {
      final GraalJsScriptEngine limited = new GraalJsScriptEngine();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  limited.eval(
                      "var i = 0; while (i < 1000000) { i++; }",
                      DefaultMessage.create(UUID.randomUUID(), Map.of())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("statements");
    } finally {
      System.clearProperty("yukta.script.max-statements");
    }
  }
}
