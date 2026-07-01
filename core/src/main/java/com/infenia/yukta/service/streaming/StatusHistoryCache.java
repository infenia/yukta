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
package com.infenia.yukta.service.streaming;

import com.infenia.yukta.model.execution.WorkflowProgress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Cache for storing recent workflow progress updates per execution.
 *
 * <p>Maintains a time-bounded, in-memory cache of WorkflowProgress snapshots. Each execution's
 * history is automatically evicted after the configured TTL expires.
 */
public interface StatusHistoryCache {

  /**
   * Record a status update for an execution.
   *
   * @param executionId the execution identifier
   * @param progress the workflow progress snapshot
   */
  void put(@NotBlank String executionId, @NotNull WorkflowProgress progress);

  /**
   * Retrieve all cached updates for an execution.
   *
   * @param executionId the execution identifier
   * @return an immutable list of cached progress updates (empty if not found or expired)
   */
  List<WorkflowProgress> get(@NotBlank String executionId);
}
