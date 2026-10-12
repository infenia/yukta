// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Backs {@link PluginMetricsReporter} with Micrometer, so the counts plugins report show up as
 * {@code yukta.plugin.events{plugin,outcome}}. The node id is left out of the tags to keep
 * cardinality bounded; it stays available in logs and the node's own telemetry.
 */
@Component
@RequiredArgsConstructor
public class MicrometerPluginMetricsReporter implements PluginMetricsReporter {

  /** Where meters are registered. */
  private final MeterRegistry meters;

  @Override
  public void increment(final String nodeId, final String metric, final String tag) {
    // A plugin that passes null must not fail inside its own stream: Micrometer rejects null tags.
    meters
        .counter(
            "yukta.plugin.events", "plugin", String.valueOf(metric), "outcome", String.valueOf(tag))
        .increment();
  }
}
