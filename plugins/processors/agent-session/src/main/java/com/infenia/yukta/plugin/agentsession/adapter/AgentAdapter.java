// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentProtocol;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Runs an agent for one message in one protocol and records what it does in the conversation of the
 * run. The plugin chooses the adapter by the protocol of the preset; clients never see the
 * difference.
 */
public interface AgentAdapter {

  /**
   * Returns the protocol this adapter speaks.
   *
   * @return the protocol
   */
  AgentProtocol protocol();

  /**
   * Runs the agent until the turn ends.
   *
   * @param run what the run needs
   * @return the outcome map the node sends on: {@code status}, {@code reason}, {@code text}, {@code
   *     sessionId}, {@code turn} and {@code exitCode}; fails when the agent could not be started
   */
  Mono<Map<String, Object>> run(AgentRun run);
}
