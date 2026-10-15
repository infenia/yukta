// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.NoMatchingBranchException;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Pins the defaults every plugin inherits, so changing one is a deliberate act. */
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.CommentRequired", "PMD.ShortVariable"})
class PluginContractDefaultsTest {

  private final Plugin plugin =
      new ProcessorPlugin() {
        @Override
        public String getType() {
          return "t";
        }

        @Override
        public Flux<Message<?>> process(final Flux<Message<?>> in, final Map<String, Object> c) {
          return in;
        }
      };

  @Test
  void defaultsAreStable() {
    assertThat(plugin.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(plugin.getDefaultBufferSize()).isEqualTo(1024);
    assertThat(plugin.getOutputPorts()).containsExactly("default");
    assertThat(plugin.getOutputPorts(Map.of())).containsExactly("default");
    assertThat(plugin.isBlocking()).isFalse();
    assertThat(plugin.isComputationallyHeavy(Map.of())).isFalse();
    assertThat(plugin.suppressOptimizationHint(Map.of())).isFalse();
    assertThat(plugin.describe()).isEqualTo(PluginDescriptor.EMPTY);
    assertThat(plugin.getConfigSchema()).isEmpty();
    StepVerifier.create(plugin.onControlSignal(null)).verifyComplete();
    StepVerifier.create(
            plugin.validateInContext(
                new WorkflowContext("n", java.util.List.of(), java.util.List.of()), Map.of()))
        .verifyComplete();
  }

  @Test
  void branchFailuresAreWorkflowExecutionExceptions() {
    assertThat(new NoMatchingBranchException("x")).isInstanceOf(WorkflowExecutionException.class);
  }
}
