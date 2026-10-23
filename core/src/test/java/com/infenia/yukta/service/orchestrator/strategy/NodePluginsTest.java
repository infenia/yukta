// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests which object serves a node. */
@NoArgsConstructor
class NodePluginsTest {

  /** Node config. */
  private static final Map<String, Object> CONFIG = Map.of();

  @Test
  void forNode_usesThePerNodeObject() {
    final ProcessorPlugin shared = mock(ProcessorPlugin.class);
    final ProcessorPlugin own = mock(ProcessorPlugin.class);
    when(shared.forNode(CONFIG)).thenReturn(own);

    assertThat(NodePlugins.forNode(shared, ProcessorPlugin.class, CONFIG)).isSameAs(own);
  }

  @Test
  void forNode_fallsBackToTheSharedPlugin_whenNullOrOfAnotherKind() {
    final ProcessorPlugin shared = mock(ProcessorPlugin.class);
    assertThat(NodePlugins.forNode(shared, ProcessorPlugin.class, CONFIG)).isSameAs(shared);

    when(shared.forNode(CONFIG)).thenReturn(mock(TerminalPlugin.class));
    assertThat(NodePlugins.forNode(shared, ProcessorPlugin.class, CONFIG)).isSameAs(shared);
  }
}
