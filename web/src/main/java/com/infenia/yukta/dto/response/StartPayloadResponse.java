// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a run was started with, as {@code ${start...}} in a node's config reads it.
 *
 * @param payload the start payload with secrets masked and the engine's {@code yukta.*} entries
 *     left out, or null when the execution log no longer holds it
 */
@Schema(description = "What a run was started with, as ${start...} in a node's config reads it")
public record StartPayloadResponse(
    @Schema(
            description =
                "The start payload, secrets masked and the engine's yukta.* entries left out;"
                    + " null when the execution log no longer holds it (see"
                    + " yukta.execution-log.retained-runs)",
            nullable = true,
            example = "{\"label\": \"gradle\", \"command\": \"./gradlew test\"}")
        @Nullable Map<String, Object> payload) {

  /**
   * Keeps an unmodifiable view of the payload.
   *
   * @param payload the masked start payload, or null
   */
  public StartPayloadResponse {
    if (payload != null) {
      payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
  }

  /**
   * Builds the response from an execution's log: the payload of its start, secrets masked.
   *
   * @param events the events of the execution
   * @return the start payload, or a response with a null payload when the log holds none
   */
  public static StartPayloadResponse fromLog(final List<ExecutionLogEvent> events) {
    return new StartPayloadResponse(
        events.stream()
            .filter(event -> event.type() == ExecutionLogEvent.Type.RUN_STARTED)
            .filter(event -> event.payload() != null)
            .findFirst()
            .map(event -> ConfigRedactor.redactPayload(event.payload()))
            .orElse(null));
  }

  /**
   * Returns a read-only copy of the payload, so the record's own map is never handed out.
   *
   * @return an unmodifiable copy of the masked start payload, or null
   */
  @Override
  public @Nullable Map<String, Object> payload() {
    return payload == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
