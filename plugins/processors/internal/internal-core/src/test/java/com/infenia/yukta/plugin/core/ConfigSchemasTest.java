// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.filter.ValidateProcessor;
import com.infenia.yukta.plugin.core.flow.DelayProcessor;
import com.infenia.yukta.plugin.core.flow.KvStoreProcessor;
import com.infenia.yukta.plugin.core.flow.LoopPredicateProcessor;
import com.infenia.yukta.plugin.core.flow.LoopStreamProcessor;
import com.infenia.yukta.plugin.core.router.AggregateProcessor;
import com.infenia.yukta.plugin.core.router.RecipientListProcessor;
import com.infenia.yukta.plugin.core.router.SplitterProcessor;
import com.infenia.yukta.plugin.core.transformer.ContentFilterProcessor;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schemas the UI renders its forms from: the fields and which are required. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class ConfigSchemasTest {

  private static List<String> names(final Plugin plugin) {
    return plugin.getConfigSchema().stream().map(ConfigField::name).toList();
  }

  private static List<String> required(final Plugin plugin) {
    return plugin.getConfigSchema().stream()
        .filter(ConfigField::required)
        .map(ConfigField::name)
        .toList();
  }

  @Test
  void recipientList() {
    final var plugin = new RecipientListProcessor();
    assertThat(names(plugin))
        .containsExactly(
            "mode",
            "recipients",
            "expression",
            "sourceField",
            "parallel",
            "errorPort",
            "strictMode");
    assertThat(required(plugin)).isEmpty();
  }

  @Test
  void splitter() {
    final var plugin = new SplitterProcessor();
    assertThat(names(plugin))
        .containsExactly(
            "itemsPath", "headerMapping", "parallel", "concurrency", "strictMode", "errorPort");
    assertThat(required(plugin)).containsExactly("itemsPath");
  }

  @Test
  void contentFilter() {
    final var plugin = new ContentFilterProcessor();
    assertThat(names(plugin))
        .containsExactly("mode", "paths", "flatten", "strictMode", "errorPort");
    assertThat(required(plugin)).containsExactly("mode", "paths");
  }

  @Test
  void loops() {
    final List<String> expected =
        List.of(
            "targetPluginId",
            "targetConfig",
            "exitCondition",
            "maxIterations",
            "maxDuration",
            "delayInterval",
            "failureStrategy");
    assertThat(names(new LoopPredicateProcessor())).isEqualTo(expected);
    assertThat(names(new LoopStreamProcessor())).isEqualTo(expected);
    assertThat(required(new LoopStreamProcessor())).containsExactly("targetPluginId");
    assertThat(required(new LoopPredicateProcessor())).containsExactly("targetPluginId");
  }

  @Test
  void delay() {
    assertThat(required(new DelayProcessor())).containsExactly("delayMs");
  }

  @Test
  void validate() {
    assertThat(names(new ValidateProcessor())).containsExactly("required", "types");
  }

  @Test
  void aggregate() {
    final var plugin = new AggregateProcessor();
    assertThat(names(plugin)).containsExactly("size", "timeoutMs");
    assertThat(required(plugin)).containsExactly("size");
  }

  @Test
  void kvStoreOffersTheFourOperations() {
    final var plugin = new KvStoreProcessor();
    assertThat(names(plugin)).containsExactly("op", "key", "namespace", "maxEntries");
    assertThat(plugin.getConfigSchema().getFirst().options())
        .containsExactly("get", "put", "incr", "delete");
  }
}
