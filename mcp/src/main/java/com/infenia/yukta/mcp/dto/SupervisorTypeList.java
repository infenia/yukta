// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The supervisor types in the catalog.
 *
 * @param types one entry per type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorTypeList(List<Entry> types) {

  /** Keeps an immutable copy of the entries. */
  public SupervisorTypeList {
    types = List.copyOf(types);
  }

  /**
   * One type.
   *
   * @param type the type name
   * @param displayName the name shown to people
   * @param category the catalog category
   * @param kind {@code SUPERVISOR}, {@code CONTROL_PLANE} or {@code COMPOSITE}
   * @param description what it does
   */
  public record Entry(
      String type, String displayName, String category, String kind, String description) {}
}
