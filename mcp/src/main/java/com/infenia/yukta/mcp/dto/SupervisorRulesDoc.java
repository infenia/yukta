// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rules document of a rules supervisor, without the settings every engine shares.
 *
 * @param supervisorId the supervisor id
 * @param version the newest kept version of the supervisor; 0 when none is kept
 * @param rules the document ({@code schemaVersion}, {@code rules}, ...), secrets shown as {@code
 *     ***}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorRulesDoc(String supervisorId, int version, Map<String, Object> rules) {

  /** Keeps an unmodifiable copy. */
  public SupervisorRulesDoc {
    rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
  }
}
