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
package com.infenia.yukta.plugin.message.control;

import java.time.Instant;

/**
 * Standard heartbeat message for the Control Bus.
 *
 * @param nodeId the unique identifier of the node
 * @param uptime the uptime of the node in milliseconds
 * @param timestamp the time the heartbeat was emitted
 */
public record ControlHeartbeat(String nodeId, long uptime, Instant timestamp) {
  /** Create a new heartbeat with current timestamp. */
  public ControlHeartbeat(final String nodeId, final long uptime) {
    this(nodeId, uptime, Instant.now());
  }
}
