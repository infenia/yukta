/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.model.control;

import java.util.Map;

/**
 * Standard statistics message for the Control Bus.
 *
 * @param nodeId the unique identifier of the node
 * @param throughput messages processed per second
 * @param latency average latency per message in milliseconds
 * @param metrics additional custom metrics provided by the plugin
 */
public record ControlStatistics(
    String nodeId, double throughput, double latency, Map<String, Object> metrics) {
  /** Compact constructor. */
  public ControlStatistics {
    metrics = Map.copyOf(metrics);
  }

  /** Create a new statistics record with empty metrics. */
  public ControlStatistics(final String nodeId, final double throughput, final double latency) {
    this(nodeId, throughput, latency, Map.of());
  }
}
