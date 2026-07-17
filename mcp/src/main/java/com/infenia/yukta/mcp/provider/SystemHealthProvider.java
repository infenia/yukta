// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlBusStatus;
import reactor.core.publisher.Mono;

/**
 * Provider for system health monitoring and control bus status. Handles metrics collection, system
 * health reporting, and execution tracking.
 */
@FunctionalInterface
public interface SystemHealthProvider {

  /**
   * Get control bus status with comprehensive monitoring information.
   *
   * @param filterType optional filter ("sessions", "plugins", "health", "executions")
   * @return Mono containing control bus status with monitoring data
   */
  Mono<ControlBusStatus> getControlBusStatus(String filterType);
}
