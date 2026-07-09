// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Tests for PluginController. */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class PluginControllerTest {

  /** Test plugin identifier. */
  private static final String TEST_PLUGIN = "test-plugin";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webTestClient;

  /** Mock registry for plugin operations. */
  private PluginRegistry registry;

  @BeforeEach
  void setUp() {
    registry = Mockito.mock(PluginRegistry.class);
    final PluginController controller = new PluginController(registry);
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
    when(plugin.getDescription()).thenReturn("desc");
    when(plugin.getUsagePattern()).thenReturn("pattern");
    when(plugin.getUiDesign()).thenReturn(Optional.of(new UiDesign("design", 100, 100)));
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
            .jsonPath("$.data.type")
            .isEqualTo(TEST_PLUGIN)
            .jsonPath("$.data.description")
            .isEqualTo("desc")
            .jsonPath("$.data.uiDesign.html")
            .isEqualTo("design")
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
    when(plugin.getDescription()).thenReturn("desc");
    when(plugin.getUsagePattern()).thenReturn("pattern");
    when(plugin.getUiDesign()).thenReturn(Optional.empty());
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
}
