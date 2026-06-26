/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.controller;

import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(PluginController.class)
class PluginControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private PluginRegistry registry;

  @Test
  void testListPlugins() {
    Plugin plugin = Mockito.mock(Plugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    webTestClient
        .get()
        .uri("/api/plugins")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].type")
        .isEqualTo("test-plugin");
  }

  @Test
  void testGetPluginDetails() {
    Plugin plugin = Mockito.mock(Plugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin.getDescription()).thenReturn("desc");
    when(plugin.getUsagePattern()).thenReturn("pattern");
    when(plugin.getUiDesign()).thenReturn(Optional.of(new UiDesign("design", 100, 100)));
    when(plugin.getOutputPorts()).thenReturn(List.of("default"));
    when(registry.get("test-plugin")).thenReturn(plugin);

    webTestClient
        .get()
        .uri("/api/plugins/test-plugin")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.type")
        .isEqualTo("test-plugin")
        .jsonPath("$.data.description")
        .isEqualTo("desc")
        .jsonPath("$.data.uiDesign.html")
        .isEqualTo("design");
  }

  @Test
  void testGetPluginDetailsNotFound() {
    when(registry.get("unknown")).thenReturn(null);

    webTestClient.get().uri("/api/plugins/unknown").exchange().expectStatus().isNotFound();
  }

  @Test
  void testGetPluginDetailsWithoutUiDesign() {
    Plugin plugin = Mockito.mock(Plugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin.getDescription()).thenReturn("desc");
    when(plugin.getUsagePattern()).thenReturn("pattern");
    when(plugin.getUiDesign()).thenReturn(Optional.empty());
    when(plugin.getOutputPorts()).thenReturn(List.of("default"));
    when(registry.get("test-plugin")).thenReturn(plugin);

    webTestClient
        .get()
        .uri("/api/plugins/test-plugin")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.uiDesign")
        .doesNotExist();
  }

  @Test
  void testListPluginsError() {
    when(registry.listPlugins()).thenThrow(new RuntimeException("Registry error"));

    webTestClient.get().uri("/api/plugins").exchange().expectStatus().is5xxServerError();
  }
}
