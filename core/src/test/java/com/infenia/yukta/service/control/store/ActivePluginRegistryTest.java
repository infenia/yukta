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
package com.infenia.yukta.service.control.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import java.util.List;
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
@SuppressWarnings("PMD.CommentRequired")
class ActivePluginRegistryTest {

  private static final String WORKFLOW_ID = "workflow-1";
  private static final String NODE_ID = "node-1";
  private static final String COMPOSITE_KEY = WORKFLOW_ID + "\0" + NODE_ID;

  @Mock private ControlSignalHandler handler1;
  @Mock private ControlSignalHandler handler2;
  @Mock private Plugin plugin;

  private ActivePluginRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ActivePluginRegistry(List.of(handler1, handler2));
  }

  @Test
  @DisplayName("should make a registered plugin lookupable")
  void register_thenLookup_returnsPlugin() {
    registry.register(WORKFLOW_ID, NODE_ID, plugin);

    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID)).containsSame(plugin);
  }

  @Test
  @DisplayName("should return empty for a node that was never registered")
  void lookup_neverRegistered_returnsEmpty() {
    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID)).isEmpty();
  }

  @Test
  @DisplayName("should remove the plugin and notify handlers on unregister")
  void register_thenUnregister_removesAndNotifiesHandlers() {
    registry.register(WORKFLOW_ID, NODE_ID, plugin);

    registry.unregister(WORKFLOW_ID, NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID)).isEmpty();
    verify(handler1).removeNode(COMPOSITE_KEY);
    verify(handler2).removeNode(COMPOSITE_KEY);
  }

  @Test
  @DisplayName("should not notify handlers when unregistering a never-registered node")
  void unregister_neverRegistered_doesNotNotifyHandlers() {
    registry.unregister(WORKFLOW_ID, NODE_ID);

    verify(handler1, never()).removeNode(COMPOSITE_KEY);
    verify(handler2, never()).removeNode(COMPOSITE_KEY);
  }

  @Test
  @DisplayName("should keep the plugin registered until every outstanding registration is released")
  void register_twice_requiresTwoUnregistersToRemove() {
    registry.register(WORKFLOW_ID, NODE_ID, plugin);
    registry.register(WORKFLOW_ID, NODE_ID, plugin);

    registry.unregister(WORKFLOW_ID, NODE_ID);
    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID))
        .as("plugin should still be reachable while one registration remains outstanding")
        .containsSame(plugin);
    verify(handler1, never()).removeNode(COMPOSITE_KEY);
    verify(handler2, never()).removeNode(COMPOSITE_KEY);

    registry.unregister(WORKFLOW_ID, NODE_ID);
    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID)).isEmpty();
    verify(handler1).removeNode(COMPOSITE_KEY);
    verify(handler2).removeNode(COMPOSITE_KEY);
  }

  @Test
  @DisplayName("should treat different node IDs under the same workflow independently")
  void register_differentNodes_storedSeparately() {
    final String altNodeId = "node-2";
    final Plugin altPlugin = mock(Plugin.class);

    registry.register(WORKFLOW_ID, NODE_ID, plugin);
    registry.register(WORKFLOW_ID, altNodeId, altPlugin);

    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID)).containsSame(plugin);
    assertThat(registry.lookup(WORKFLOW_ID, altNodeId)).containsSame(altPlugin);

    registry.unregister(WORKFLOW_ID, NODE_ID);

    assertThat(registry.lookup(WORKFLOW_ID, NODE_ID)).isEmpty();
    assertThat(registry.lookup(WORKFLOW_ID, altNodeId)).containsSame(altPlugin);
  }
}
