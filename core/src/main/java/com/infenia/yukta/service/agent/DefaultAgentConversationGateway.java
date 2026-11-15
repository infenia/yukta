// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The gateway a plugin takes through an {@code ObjectProvider<AgentConversationGateway>}. It finds
 * the root of the run's tree of runs, so a conversation of a child run can be read together with
 * those of the runs around it, and opens the conversation in the {@link AgentConversationService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DefaultAgentConversationGateway implements AgentConversationGateway {

  /** Records the conversations. */
  private final AgentConversationService service;

  /** Knows the tree of runs. */
  private final TaskTrackerService tracker;

  @Override
  public Mono<AgentConversationHandle> open(final AgentConversationSpec spec) {
    return Mono.fromSupplier(() -> rootOf(spec.who().executionId()))
        .flatMap(root -> service.open(spec, root));
  }

  /** The root of the tree, or the run itself when the tracker does not know it (yet). */
  private String rootOf(final String executionId) {
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    if (progress == null) {
      log.atDebug()
          .addKeyValue("executionId", executionId)
          .log("The run is not known to the tracker; its conversation is its own root");
      return executionId;
    }
    final String root = progress.rootExecutionId();
    return Objects.requireNonNullElse(root, executionId);
  }
}
