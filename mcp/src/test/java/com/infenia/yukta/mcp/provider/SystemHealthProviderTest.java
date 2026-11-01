// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.ExecutionRecord;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SystemHealthProviderTest {

  private SystemHealthProvider provider;
  private PluginRegistry registry;
  private SessionService sessionService;
  private ControlBusGateway controlBus;

  @BeforeEach
  void setUp() {
    registry = mock(PluginRegistry.class);
    sessionService = mock(SessionService.class);
    controlBus = mock(ControlBusGateway.class);
    provider = new SystemHealthProvider(registry, sessionService, controlBus);
  }

  private WorkflowExecutionSummary execution(final String id, final TaskStatus status) {
    final Instant start = Instant.now().minus(Duration.ofMinutes(1));
    final Instant end = status == TaskStatus.RUNNING ? null : Instant.now();
    return new WorkflowExecutionSummary(id, "wf-1", status, start, end);
  }

  @Test
  void testFullStatusWithRealSessionAndExecutionData() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.getType()).thenReturn("gradle");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1"));
    final var wf1 =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf-1", "d", List.of(), List.of());
    final var wf2 =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf-2", "d", List.of(), List.of());
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s1",
            "Test Session",
            "desc",
            "initiator",
            Map.of(),
            "/path",
            Map.of("wf-1", wf1, "wf-2", wf2));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(execution("e1", TaskStatus.RUNNING), execution("e2", TaskStatus.SUCCESS))));

    StepVerifier.create(provider.getControlBusStatus(null))
        .assertNext(
            status -> {
              verify(controlBus, times(1)).getHistory("s1");
              assertThat(status.activeSessions()).hasSize(1);
              assertThat(status.activeSessions().get(0).sessionId()).isEqualTo("s1");
              assertThat(status.activeSessions().get(0).activeExecutions()).isEqualTo(1);
              assertThat(status.activeSessions().get(0).totalWorkflows()).isEqualTo(2);

              assertThat(status.pluginRegistry()).hasSize(1);
              assertThat(status.pluginRegistry().get(0).type()).isEqualTo("gradle");

              assertThat(status.systemHealth()).isNotNull();
              assertThat(status.systemHealth().uptime()).isNotBlank();

              assertThat(status.recentExecutions()).hasSize(2);
              assertThat(status.recentExecutions())
                  .anyMatch(r -> r.executionId().equals("e1") && r.status().equals("RUNNING"));
            })
        .verifyComplete();
  }

  @Test
  void testPluginsFilterSkipsSessionsAndExecutions() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.getType()).thenReturn("gradle");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    StepVerifier.create(provider.getControlBusStatus("plugins"))
        .assertNext(
            status -> {
              assertThat(status.activeSessions()).isEmpty();
              assertThat(status.pluginRegistry()).hasSize(1);
              assertThat(status.recentExecutions()).isEmpty();
            })
        .verifyComplete();
  }

  @Test
  void testSessionsFilterSkipsPlugins() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1"));
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s1", "Test Session", "desc", "initiator", Map.of(), "/path", Map.of());
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    when(controlBus.getHistory("s1")).thenReturn(Mono.just(List.of()));

    StepVerifier.create(provider.getControlBusStatus("sessions"))
        .assertNext(
            status -> {
              assertThat(status.activeSessions()).hasSize(1);
              assertThat(status.activeSessions().get(0).totalWorkflows()).isZero();
              assertThat(status.pluginRegistry()).isEmpty();
            })
        .verifyComplete();
  }

  @Test
  void testBlankFilterBehavesLikeNull() {
    when(registry.listPlugins()).thenReturn(List.of());
    when(sessionService.getSessionIds()).thenReturn(Flux.empty());

    StepVerifier.create(provider.getControlBusStatus(" "))
        .assertNext(status -> assertThat(status.systemHealth().uptime()).isNotBlank())
        .verifyComplete();
  }

  @Test
  void testRecentExecutionsAreNewestFirstAcrossSessions() {
    final Instant base = LocalDateTime.of(2026, 1, 1, 12, 0).toInstant(ZoneOffset.UTC);
    final List<WorkflowExecutionSummary> s1History =
        IntStream.range(0, 12)
            .mapToObj(
                i ->
                    new WorkflowExecutionSummary(
                        "s1-e" + i,
                        "wf-1",
                        TaskStatus.SUCCESS,
                        base.plus(Duration.ofMinutes(2L * i)),
                        base.plus(Duration.ofMinutes(2L * i + 1))))
            .toList();
    final List<WorkflowExecutionSummary> s2History =
        IntStream.range(0, 12)
            .mapToObj(
                i ->
                    new WorkflowExecutionSummary(
                        "s2-e" + i,
                        "wf-2",
                        TaskStatus.SUCCESS,
                        base.plus(Duration.ofMinutes(2L * i + 1)),
                        base.plus(Duration.ofMinutes(2L * i + 2))))
            .toList();
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(controlBus.getHistory("s1")).thenReturn(Mono.just(s1History));
    when(controlBus.getHistory("s2")).thenReturn(Mono.just(s2History));

    StepVerifier.create(provider.getControlBusStatus("executions"))
        .assertNext(
            status -> {
              final List<ExecutionRecord> recent = status.recentExecutions();
              assertThat(recent).hasSize(20);
              assertThat(recent.get(0).executionId()).isEqualTo("s2-e11");
              assertThat(recent.get(1).executionId()).isEqualTo("s1-e11");
              assertThat(recent)
                  .extracting(ExecutionRecord::executionId)
                  .doesNotContain("s1-e0", "s2-e0", "s1-e1", "s2-e1");
            })
        .verifyComplete();
  }

  @Test
  void testUnsupportedFilterYieldsError() {
    StepVerifier.create(provider.getControlBusStatus("bogus"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Unsupported filter: bogus")
                    && error.getMessage().contains("sessions, executions, plugins, health"))
        .verify();
  }

  @Test
  void testHealthFilterOnlyPopulatesHealth() {
    StepVerifier.create(provider.getControlBusStatus("health"))
        .assertNext(
            status -> {
              assertThat(status.activeSessions()).isEmpty();
              assertThat(status.pluginRegistry()).isEmpty();
              assertThat(status.recentExecutions()).isEmpty();
              assertThat(status.systemHealth().uptime()).isNotBlank();
            })
        .verifyComplete();
  }

  @Test
  void testExecutionsFilterSkipsFailingHistory() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(controlBus.getHistory("s1")).thenReturn(Mono.error(new IllegalStateException("boom")));
    when(controlBus.getHistory("s2"))
        .thenReturn(Mono.just(List.of(execution("e2", TaskStatus.SUCCESS))));

    StepVerifier.create(provider.getControlBusStatus("executions"))
        .assertNext(
            status -> {
              assertThat(status.activeSessions()).isEmpty();
              assertThat(status.recentExecutions()).hasSize(1);
              assertThat(status.recentExecutions().get(0).executionId()).isEqualTo("e2");
              assertThat(status.recentExecutions().get(0).duration()).endsWith("ms");
            })
        .verifyComplete();
  }

  @Test
  void testFailingSessionConfigIsSkipped() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(sessionService.getSessionConfig("s1"))
        .thenReturn(Mono.error(new RuntimeException("boom")));
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s2", "Test Session", "desc", "initiator", Map.of(), "/path", Map.of());
    when(sessionService.getSessionConfig("s2")).thenReturn(Mono.just(config));
    when(controlBus.getHistory("s2")).thenReturn(Mono.just(List.of()));

    StepVerifier.create(provider.getControlBusStatus("sessions"))
        .assertNext(
            status -> {
              assertThat(status.activeSessions()).hasSize(1);
              assertThat(status.activeSessions().get(0).sessionId()).isEqualTo("s2");
            })
        .verifyComplete();
  }

  @Test
  void testOnlyNonTerminalStatusesCountAsActive() {
    when(registry.listPlugins()).thenReturn(List.of());
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1"));
    final var wf =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf-1", "d", List.of(), List.of());
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s1", "Test Session", "desc", "initiator", Map.of(), "/path", Map.of("wf-1", wf));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    execution("e1", TaskStatus.RUNNING),
                    execution("e2", TaskStatus.SUCCESS),
                    execution("e3", TaskStatus.TIMED_OUT),
                    execution("e4", TaskStatus.SUCCESS),
                    execution("e5", TaskStatus.PENDING))));

    StepVerifier.create(provider.getControlBusStatus(null))
        .assertNext(
            status ->
                // RUNNING and PENDING are active; SUCCESS, TIMED_OUT are not
                assertThat(status.activeSessions().get(0).activeExecutions()).isEqualTo(2))
        .verifyComplete();
  }

  @Test
  void testNullStatusAndNullStartDoNotBreakTheStatus() {
    when(registry.listPlugins()).thenReturn(List.of());
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1"));
    final var wf =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf-1", "d", List.of(), List.of());
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s1", "Test Session", "desc", "initiator", Map.of(), "/path", Map.of("wf-1", wf));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    new WorkflowExecutionSummary("e1", "wf-1", null, null, null),
                    new WorkflowExecutionSummary(
                        "e2", "wf-1", TaskStatus.SUCCESS, null, Instant.now()))));

    StepVerifier.create(provider.getControlBusStatus(null))
        .assertNext(
            status -> {
              assertThat(status.activeSessions().get(0).activeExecutions()).isEqualTo(1);
              assertThat(status.recentExecutions())
                  .extracting(ExecutionRecord::duration)
                  .contains("unknown", "running");
            })
        .verifyComplete();
  }

  @Test
  void whoami_describesTheKeyInTheContext() {
    final var who =
        provider
            .whoami()
            .contextWrite(
                reactor.util.context.Context.of(
                    CallerIdentity.CONTEXT_KEY,
                    new ApiKeys.Match("alice", ApiRole.OPERATOR, java.util.Set.of("s1"))))
            .block();

    org.assertj.core.api.Assertions.assertThat(who.keyName()).isEqualTo("alice");
    org.assertj.core.api.Assertions.assertThat(who.role()).isEqualTo("OPERATOR");
    org.assertj.core.api.Assertions.assertThat(who.sessions()).containsExactly("s1");
    org.assertj.core.api.Assertions.assertThat(who.permissions())
        .containsEntry("startRuns", true)
        .containsEntry("changeLimits", false);
    org.assertj.core.api.Assertions.assertThat(provider.whoami().block().authEnabled()).isFalse();
  }
}
