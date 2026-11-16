// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static com.infenia.yukta.service.agent.AgentTestSupport.settings;
import static com.infenia.yukta.service.agent.AgentTestSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The gateway files a conversation under the root of the run's tree. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.AvoidDuplicateLiterals"})
class DefaultAgentConversationGatewayTest {

  private final InMemoryAgentConversationStore store =
      new InMemoryAgentConversationStore(10, 100, 1000);
  private final AgentConversationService service =
      new AgentConversationService(
          store,
          new InputRedactor(List.of()),
          settings(1000, 1000, 100),
          JsonMapper.builder().build(),
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final DefaultAgentConversationGateway gateway =
      new DefaultAgentConversationGateway(service, tracker);

  @AfterEach
  void tearDown() {
    service.destroy();
  }

  private String rootOfOpened() {
    final AgentConversationHandle handle = gateway.open(spec()).block();
    return java.util.Objects.requireNonNull(store.find(handle.id()).block()).rootExecutionId();
  }

  @Test
  void childRunIsFiledUnderTheRootOfItsTree() {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.rootExecutionId()).thenReturn("the-root");
    when(tracker.getProgressByExecutionId("exec-1")).thenReturn(progress);

    assertThat(rootOfOpened()).isEqualTo("the-root");
  }

  @Test
  void runWithoutTheParentIsItsOwnRoot() {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(tracker.getProgressByExecutionId("exec-1")).thenReturn(progress);

    assertThat(rootOfOpened()).isEqualTo("exec-1");
  }

  @Test
  void runTheTrackerDoesNotKnowIsItsOwnRoot() {
    when(tracker.getProgressByExecutionId("exec-1")).thenReturn(null);

    assertThat(rootOfOpened()).isEqualTo("exec-1");
  }
}
