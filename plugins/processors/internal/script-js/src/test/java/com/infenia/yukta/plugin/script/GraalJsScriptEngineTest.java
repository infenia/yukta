// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
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
    assertThatThrownBy(() -> run("Java.type('java.lang.Runtime').getRuntime().exec('true')"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> run("Java.type('java.lang.ProcessBuilder')"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> run("payload.getClass().forName('java.lang.Runtime')"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void scriptsCannotChangeTheMessageTheyWereGiven() {
    final Map<String, Object> payload = new HashMap<>();
    payload.put("n", 2);
    payload.put("list", new ArrayList<>(List.of(1, 2)));

    final Object result =
        engine.eval(
            "payload.n = 99; payload.list[0] = 42; payload.extra = 'x'; payload.n",
            DefaultMessage.create(UUID.randomUUID(), payload));

    assertThat(result).as("the script sees its own changes").isEqualTo(99);
    assertThat(payload).containsOnlyKeys("n", "list").containsEntry("n", 2);
    assertThat(payload.get("list")).isEqualTo(List.of(1, 2));
  }

  @Test
  void javaObjectsInThePayloadAreSeenAsTextNotReached() {
    final AtomicBoolean flag = new AtomicBoolean();
    final DefaultMessage<?> message =
        DefaultMessage.create(UUID.randomUUID(), Map.of("flag", flag));

    assertThatThrownBy(() -> engine.eval("payload.flag.set(true)", message))
        .isInstanceOf(RuntimeException.class);

    assertThat(flag).isFalse();
    assertThat(engine.eval("payload.flag", message)).isEqualTo("false");
  }

  @Test
  void theMessageIsPlainDataWithoutMethods() {
    final Message<?> message =
        DefaultMessage.create(UUID.randomUUID(), Map.of("n", 1)).withMetadata(Map.of("k", "v"));

    assertThat(engine.eval("message.metadata.k", message)).isEqualTo("v");
    assertThat(engine.eval("message.payload.n", message)).isEqualTo(1);
    assertThat(engine.eval("message.id", message)).isEqualTo(message.id());
    assertThatThrownBy(() -> engine.eval("message.withTraceId('x')", message))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void oversizedInputIsRefused() {
    final List<Integer> big = IntStream.range(0, 1_100_000).boxed().toList();

    assertThatThrownBy(
            () -> engine.eval("1", DefaultMessage.create(UUID.randomUUID(), Map.of("big", big))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
  }

  @Test
  void oversizedResultIsRefused() {
    assertThatThrownBy(() -> run("Array.from({length: 1500000}, (_, i) => i)"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("too large");
  }

  @Test
  void hugeStringIsRefused() {
    assertThatThrownBy(() -> run("'x'.repeat(20000000).length"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void scriptThatNeverEndsIsCancelled() {
    System.setProperty("yukta.script.timeout-ms", "300");
    try {
      final GraalJsScriptEngine limited = new GraalJsScriptEngine();
      assertThatThrownBy(
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
      assertThatThrownBy(
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
