// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.filter.ValidateProcessor;
import com.infenia.yukta.plugin.core.router.AggregateProcessor;
import com.infenia.yukta.plugin.core.router.BranchProcessor;
import com.infenia.yukta.plugin.core.router.RecipientListProcessor;
import com.infenia.yukta.plugin.core.router.SplitterProcessor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.List;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The Spring constructors accept an optional reporter bean. */
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class PluginMetricsWiringTest {

  @Test
  void processorsBuildWithAndWithoutReporterBean() {
    final StaticListableBeanFactory with = new StaticListableBeanFactory();
    with.addBean("reporter", (PluginMetricsReporter) (node, metric, tag) -> {});
    final ObjectProvider<PluginMetricsReporter> present =
        with.getBeanProvider(PluginMetricsReporter.class);
    final ObjectProvider<PluginMetricsReporter> absent =
        new StaticListableBeanFactory().getBeanProvider(PluginMetricsReporter.class);

    final List<Function<ObjectProvider<PluginMetricsReporter>, ProcessorPlugin>> factories =
        List.of(
            BranchProcessor::new,
            SplitterProcessor::new,
            RecipientListProcessor::new,
            AggregateProcessor::new,
            ValidateProcessor::new);
    for (final var factory : factories) {
      assertThat(factory.apply(present).getType()).isEqualTo(factory.apply(absent).getType());
    }
  }
}
