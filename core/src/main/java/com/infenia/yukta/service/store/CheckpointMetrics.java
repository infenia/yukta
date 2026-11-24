// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Publishes how many bytes of node output the checkpoint store holds. */
@Component
@RequiredArgsConstructor
public class CheckpointMetrics implements MeterBinder {

  /** The store to observe; one that cannot tell its size is not published. */
  private final NodeCheckpointStore store;

  @Override
  public void bindTo(final MeterRegistry registry) {
    if (store instanceof ByteCounted counted) {
      Gauge.builder("yukta.checkpoint.bytes", counted, ByteCounted::bytesHeld)
          .baseUnit("bytes")
          .description("Node output held for restart and resume")
          .register(registry);
      FunctionCounter.builder("yukta.checkpoint.errors", counted, ByteCounted::markerFailures)
          .description("Node ends that could not be made durable")
          .register(registry);
    }
  }
}
