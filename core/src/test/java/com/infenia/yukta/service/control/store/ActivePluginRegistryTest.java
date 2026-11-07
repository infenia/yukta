// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.core.Plugin;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivePluginRegistry")
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ActivePluginRegistryTest {

  private static final String WORKFLOW_ID = "workflow-1";
  private static final String NODE_ID = "node-1";

  @Mock private Plugin plugin;

  private ActivePluginRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ActivePluginRegistry();
  }

  @Test
  @DisplayName("should make a registered plugin lookupable")
  void register_thenLookup_returnsPlugin() {
    registry.register(WORKFLOW_ID, null, NODE_ID, plugin);

    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).containsSame(plugin);
  }

  @Test
  @DisplayName("should keep the same node of two runs apart")
  void sameNodeOfTwoRuns_isRegisteredPerRun() {
    registry.register(WORKFLOW_ID, "run-1", NODE_ID, plugin);
    registry.register(WORKFLOW_ID, "run-2", NODE_ID, plugin);

    registry.unregister(WORKFLOW_ID, "run-1", NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, "run-1", NODE_ID)).isEmpty();
    assertThat(registry.lookup(WORKFLOW_ID, "run-2", NODE_ID)).containsSame(plugin);
  }

  @Test
  @DisplayName("should refcount a registration of one run")
  void sameRunRegisteredTwice_isRefcounted() {
    registry.register(WORKFLOW_ID, "run-1", NODE_ID, plugin);
    registry.register(WORKFLOW_ID, "run-1", NODE_ID, plugin);

    registry.unregister(WORKFLOW_ID, "run-1", NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, "run-1", NODE_ID)).containsSame(plugin);
  }

  @Test
  @DisplayName("should return empty for a node that was never registered")
  void lookup_neverRegistered_returnsEmpty() {
    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).isEmpty();
  }

  @Test
  @DisplayName("should remove the plugin on unregister")
  void register_thenUnregister_removes() {
    registry.register(WORKFLOW_ID, null, NODE_ID, plugin);

    registry.unregister(WORKFLOW_ID, null, NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).isEmpty();
  }

  @Test
  @DisplayName("should ignore unregistering a never-registered node")
  void unregister_neverRegistered_isIgnored() {
    registry.unregister(WORKFLOW_ID, null, NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).isEmpty();
  }

  @Test
  @DisplayName("should keep the plugin registered until every outstanding registration is released")
  void register_twice_requiresTwoUnregistersToRemove() {
    registry.register(WORKFLOW_ID, null, NODE_ID, plugin);
    registry.register(WORKFLOW_ID, null, NODE_ID, plugin);

    registry.unregister(WORKFLOW_ID, null, NODE_ID);
    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID))
        .as("plugin should still be reachable while one registration remains outstanding")
        .containsSame(plugin);

    registry.unregister(WORKFLOW_ID, null, NODE_ID);
    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).isEmpty();
  }

  @Test
  @DisplayName("should treat different node IDs under the same workflow independently")
  void register_differentNodes_storedSeparately() {
    final String altNodeId = "node-2";
    final Plugin altPlugin = mock(Plugin.class);

    registry.register(WORKFLOW_ID, null, NODE_ID, plugin);
    registry.register(WORKFLOW_ID, null, altNodeId, altPlugin);

    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).containsSame(plugin);
    assertThat(registry.lookup(WORKFLOW_ID, null, altNodeId)).containsSame(altPlugin);

    registry.unregister(WORKFLOW_ID, null, NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, null, NODE_ID)).isEmpty();
    assertThat(registry.lookup(WORKFLOW_ID, null, altNodeId)).containsSame(altPlugin);
  }
}
