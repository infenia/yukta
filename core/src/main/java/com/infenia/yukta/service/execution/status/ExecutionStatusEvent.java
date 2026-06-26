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
package com.infenia.yukta.service.execution.status;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Event representing a status update for a node execution.
 *
 * <p>Published by ExecutionStatusPublisher to notify listeners of status changes during workflow
 * execution (RUNNING, SUCCESS, FAILURE, etc.).
 */
@SuppressFBWarnings("EI_EXPOSE_REP")
public record ExecutionStatusEvent(
    @NotBlank String executionId,
    @NotBlank String nodeId,
    @NotBlank String workflowId,
    @NotBlank String sessionId,
    @NotBlank String status,
    @NotBlank String module,
    @Nullable Map<String, Object> metadata,
    @Nullable Throwable error,
    @NotNull Instant timestamp) {

  /**
   * Compact constructor to ensure metadata is immutable.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param workflowId the workflow identifier
   * @param sessionId the session identifier
   * @param status the status value
   * @param module the module name
   * @param metadata optional metadata map
   * @param error optional error/exception
   * @param timestamp the event timestamp
   */
  public ExecutionStatusEvent {
    if (metadata != null) {
      metadata = Map.copyOf(metadata);
    }
  }

  @Override
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  @Override
  public Throwable error() {
    return error;
  }

  /**
   * Create a new ExecutionStatusEvent with current timestamp.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param workflowId the workflow identifier
   * @param sessionId the session identifier
   * @param status the status value (e.g., "RUNNING", "SUCCESS", "FAILURE")
   * @param module the module name
   * @param metadata optional metadata map
   * @param error optional error/exception
   * @return a new ExecutionStatusEvent with current timestamp
   */
  @SuppressWarnings({"PMD.UseObjectForClearerAPI", "PMD.ShortMethodName"})
  public static ExecutionStatusEvent of(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      @NotBlank final String workflowId,
      @NotBlank final String sessionId,
      @NotBlank final String status,
      @NotBlank final String module,
      @Nullable final Map<String, Object> metadata,
      @Nullable final Throwable error) {
    return new ExecutionStatusEvent(
        executionId, nodeId, workflowId, sessionId, status, module, metadata, error, Instant.now());
  }
}
