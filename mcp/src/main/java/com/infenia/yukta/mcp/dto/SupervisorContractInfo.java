// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The supervisor contract.
 *
 * @param version the contract version
 * @param operations the names of its operations
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorContractInfo(int version, List<String> operations) {

  /** Keeps an immutable copy of the operation names. */
  public SupervisorContractInfo {
    operations = List.copyOf(operations);
  }
}
