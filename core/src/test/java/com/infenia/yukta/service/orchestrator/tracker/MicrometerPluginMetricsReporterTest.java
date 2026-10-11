// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class MicrometerPluginMetricsReporterTest {

  private static final String SQL = "sql";

  @Test
  void countsByPluginAndOutcomeIgnoringNodeId() {
    final var registry = new SimpleMeterRegistry();
    final var reporter = new MicrometerPluginMetricsReporter(registry);

    reporter.increment("n1", SQL, "OK");
    reporter.increment("n2", SQL, "OK");
    reporter.increment("n1", SQL, "ERROR");

    assertThat(registry.counter("yukta.plugin.events", "plugin", SQL, "outcome", "OK").count())
        .isEqualTo(2.0);
    assertThat(registry.counter("yukta.plugin.events", "plugin", SQL, "outcome", "ERROR").count())
        .isEqualTo(1.0);
  }
}
