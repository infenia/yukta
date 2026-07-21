// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.ExecutionRecord;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultSystemHealthProviderTest {

  private DefaultSystemHealthProvider provider;
  private PluginRegistry registry;
  private SessionService sessionService;
  private ControlBusGateway controlBus;

  @BeforeEach
  void setUp() {
    registry = mock(PluginRegistry.class);
    sessionService = mock(SessionService.class);
    controlBus = mock(ControlBusGateway.class);
    provider = new DefaultSystemHealthProvider(registry, sessionService, controlBus);
  }

  private WorkflowExecutionSummary execution(final String id, final String status) {
    final LocalDateTime start = LocalDateTime.now(ZoneId.systemDefault()).minusMinutes(1);
    final LocalDateTime end =
        "RUNNING".equals(status) ? null : LocalDateTime.now(ZoneId.systemDefault());
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
        .thenReturn(List.of(execution("e1", "RUNNING"), execution("e2", "COMPLETED")));

    StepVerifier.create(provider.getControlBusStatus(null))
        .assertNext(
            status -> {
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
    when(controlBus.getHistory("s1")).thenReturn(List.of());

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
    final LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0);
    final List<WorkflowExecutionSummary> s1History =
        IntStream.range(0, 12)
            .mapToObj(
                i ->
                    new WorkflowExecutionSummary(
                        "s1-e" + i,
                        "wf-1",
                        "COMPLETED",
                        base.plusMinutes(2L * i),
                        base.plusMinutes(2L * i + 1)))
            .toList();
    final List<WorkflowExecutionSummary> s2History =
        IntStream.range(0, 12)
            .mapToObj(
                i ->
                    new WorkflowExecutionSummary(
                        "s2-e" + i,
                        "wf-2",
                        "COMPLETED",
                        base.plusMinutes(2L * i + 1),
                        base.plusMinutes(2L * i + 2)))
            .toList();
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(controlBus.getHistory("s1")).thenReturn(s1History);
    when(controlBus.getHistory("s2")).thenReturn(s2History);

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
    when(controlBus.getHistory("s1")).thenThrow(new IllegalStateException("boom"));
    when(controlBus.getHistory("s2")).thenReturn(List.of(execution("e2", "COMPLETED")));

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
    when(controlBus.getHistory("s2")).thenReturn(List.of());

    StepVerifier.create(provider.getControlBusStatus("sessions"))
        .assertNext(
            status -> {
              assertThat(status.activeSessions()).hasSize(1);
              assertThat(status.activeSessions().get(0).sessionId()).isEqualTo("s2");
            })
        .verifyComplete();
  }
}
