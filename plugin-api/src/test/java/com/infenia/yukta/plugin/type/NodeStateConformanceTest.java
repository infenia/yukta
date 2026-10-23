// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests the conformance check with a plugin that shares state and one that does not. */
@NoArgsConstructor
class NodeStateConformanceTest {

  /** Config of node A. */
  private static final Map<String, Object> CONFIG_A = Map.of("prefix", "a");

  /** Config of node B. */
  private static final Map<String, Object> CONFIG_B = Map.of("prefix", "b");

  /** Keeps the node config in a field of the shared instance: the mistake the check catches. */
  private static class FieldStatePlugin implements ProcessorPlugin {
    /** The mistake: node config kept in the shared instance. */
    private String prefix;

    @Override
    public String getType() {
      return "FIELD_STATE";
    }

    @Override
    public Flux<Message<?>> process(
        final Flux<Message<?>> input, final Map<String, Object> config) {
      prefix = (String) config.get("prefix");
      return input.map(
          m -> DefaultMessage.create(java.util.UUID.randomUUID(), prefix + m.payload()));
    }
  }

  /** Same plugin, but each node gets its own instance. */
  private static final class PerNodePlugin extends FieldStatePlugin {
    @Override
    public Plugin forNode(final Map<String, Object> config) {
      return new FieldStatePlugin();
    }
  }

  @Test
  void forNode_defaultsToThePluginItself() {
    final ProcessorPlugin plugin = new FieldStatePlugin();
    assertThat(plugin.forNode(CONFIG_A)).isSameAs(plugin);
  }

  @Test
  void sharedFieldState_failsTheCheck() {
    assertThatThrownBy(
            () ->
                NodeStateConformance.assertNodesDoNotShareState(
                    new FieldStatePlugin(), CONFIG_A, CONFIG_B, List.of("1", "2"), List.of("1")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("changed when another node ran");
  }

  @Test
  void perNodeState_passesTheCheck() {
    assertThatCode(
            () ->
                NodeStateConformance.assertNodesDoNotShareState(
                    new PerNodePlugin(), CONFIG_A, CONFIG_B, List.of("1", "2"), List.of("1")))
        .doesNotThrowAnyException();
  }
}
