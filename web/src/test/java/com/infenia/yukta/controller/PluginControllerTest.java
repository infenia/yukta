// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** Tests for PluginController. */
@NoArgsConstructor
class PluginControllerTest {

  /** Test plugin identifier. */
  private static final String TEST_PLUGIN = "test-plugin";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webTestClient;

  /** Mock registry for plugin operations. */
  private PluginRegistry registry;

  /** Mock sessions, whose workflows use the plugins. */
  private SessionService sessions;

  @BeforeEach
  void setUp() {
    registry = Mockito.mock(PluginRegistry.class);
    sessions = Mockito.mock(SessionService.class);
    final PluginController controller = new PluginController(registry, sessions);
    webTestClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testListPlugins() {
    final Plugin plugin = Mockito.mock(Plugin.class);
    when(plugin.getType()).thenReturn(TEST_PLUGIN);
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    final var result =
        webTestClient
            .get()
            .uri("/api/plugins")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data[0].type")
            .isEqualTo(TEST_PLUGIN)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetPluginDetails() {
    final Plugin plugin = Mockito.mock(Plugin.class);
    when(plugin.getType()).thenReturn(TEST_PLUGIN);
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin.describe())
        .thenReturn(
            new PluginDescriptor(
                "desc", "pattern", Optional.of(new UiDesign("blue", "api", "Test", 100, 100))));
    when(plugin.getOutputPorts()).thenReturn(List.of("default"));
    when(plugin.getConfigSchema())
        .thenReturn(List.of(ConfigField.required("url", ConfigField.Type.STRING, "the url")));
    when(registry.get(TEST_PLUGIN)).thenReturn(plugin);

    final var result =
        webTestClient
            .get()
            .uri("/api/plugins/test-plugin")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.type")
            .isEqualTo(TEST_PLUGIN)
            .jsonPath("$.data.description")
            .isEqualTo("desc")
            .jsonPath("$.data.uiDesign.label")
            .isEqualTo("Test")
            .jsonPath("$.data.configSchema[0].name")
            .isEqualTo("url")
            .jsonPath("$.data.configSchema[0].required")
            .isEqualTo(true)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetPluginDetailsNotFound() {
    when(registry.get("unknown")).thenReturn(null);

    final var result =
        webTestClient
            .get()
            .uri("/api/plugins/unknown")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetPluginDetailsWithoutUiDesign() {
    final Plugin plugin = Mockito.mock(Plugin.class);
    when(plugin.getType()).thenReturn(TEST_PLUGIN);
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin.describe()).thenReturn(new PluginDescriptor("desc", "pattern", Optional.empty()));
    when(plugin.getOutputPorts()).thenReturn(List.of("default"));
    when(registry.get(TEST_PLUGIN)).thenReturn(plugin);

    final var result =
        webTestClient
            .get()
            .uri("/api/plugins/test-plugin")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.uiDesign")
            .doesNotExist()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  @ExpectedErrorLog("HttpWebHandlerAdapter")
  void testListPluginsError() {
    when(registry.listPlugins()).thenThrow(new RuntimeException("Registry error"));

    final var result =
        webTestClient
            .get()
            .uri("/api/plugins")
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  private static WorkflowDefinition workflow(final String id, final String... types) {
    final List<WorkflowDefinition.Node> nodes = new ArrayList<>();
    for (int i = 0; i < types.length; i++) {
      nodes.add(new WorkflowDefinition.Node("n" + i, types[i], Map.of()));
    }
    return new WorkflowDefinition(id, "d", nodes, List.of());
  }

  private static SessionConfigResponse session(final String id, final WorkflowDefinition... wfs) {
    final Map<String, WorkflowDefinition> byId =
        Arrays.stream(wfs).collect(Collectors.toMap(WorkflowDefinition::workflowId, wf -> wf));
    return new SessionConfigResponse(id, "n", "d", "i", Map.of(), "/p", byId);
  }

  @Test
  void testUsageListsTheWorkflowsWithNodesOfTheType_sorted() {
    when(sessions.getAllSessionConfigs())
        .thenReturn(
            Flux.just(
                session("zeta", workflow("tool", "PROCESS_EXECUTOR")),
                session(
                    "dogfood",
                    workflow("check", "PROCESS_EXECUTOR", "BRANCH", "PROCESS_EXECUTOR"),
                    workflow("notify", "HTTP_REQUEST"))));

    final EntityExchangeResult<byte[]> result =
        webTestClient
            .get()
            .uri("/api/plugins/PROCESS_EXECUTOR/usage")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.length()")
            .isEqualTo(2)
            .jsonPath("$.data[0].sessionId")
            .isEqualTo("dogfood")
            .jsonPath("$.data[0].workflowId")
            .isEqualTo("check")
            .jsonPath("$.data[0].nodes")
            .isEqualTo(2)
            .jsonPath("$.data[1].sessionId")
            .isEqualTo("zeta")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }
}
