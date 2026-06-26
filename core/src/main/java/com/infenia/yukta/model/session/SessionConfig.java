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
package com.infenia.yukta.model.session;

import java.util.Map;

/**
 * Internal record for session configuration state stored in SessionConfigStore.
 *
 * @param projectPath the project path for the session
 * @param initiator the initiator name
 * @param initiatedTime the timestamp when the session was initiated
 * @param tags additional tags for the session
 * @param description a human-readable description of the session
 */
public record SessionConfig(
    String projectPath,
    String initiator,
    String initiatedTime,
    Map<String, String> tags,
    String description) {

  /** Compact constructor for immutability. */
  public SessionConfig {
    tags = tags != null ? Map.copyOf(tags) : Map.of();
  }
}
