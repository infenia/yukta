// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Administrative command for a node. On the wire it is the bare JSON object that becomes the
 * command message payload.
 *
 * @param payload the command fields, at most 100 entries
 */
@Schema(description = "Command payload as a JSON object", example = "{\"action\": \"flush\"}")
public record NodeCommandRequest(
    @NotEmpty(message = "Command payload must not be empty")
        @Size(max = 100, message = "Command payload may have at most 100 entries")
        @JsonValue
        Map<String, Object> payload) {

  /**
   * Creates the request from the bare JSON object.
   *
   * @param payload the command fields
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public NodeCommandRequest {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }
}
