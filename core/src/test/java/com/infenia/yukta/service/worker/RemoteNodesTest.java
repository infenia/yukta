// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "unchecked"})
class RemoteNodesTest {

  private static final Map<String, Object> PLACED =
      Map.of("engine", Map.of("placement", Map.of("pool", "gpu")));

  private final ProcessorPlugin local = mock(ProcessorPlugin.class);
  private final RemoteNodeExecutor executor = mock(RemoteNodeExecutor.class);

  private RemoteNodes nodes(final RemoteNodeExecutor available) {
    final ObjectProvider<RemoteNodeExecutor> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(available);
    return new RemoteNodes(provider);
  }

  @Test
  void nodeWithoutPlacement_keepsLocalPlugin() {
    assertThat(nodes(executor).place("T", Map.of(), local)).isSameAs(local);
    assertThat(nodes(null).place("T", Map.of("engine", Map.of("retry", Map.of())), local))
        .isSameAs(local);
  }

  @Test
  void placedNode_runsCallsOnWorker() {
    when(local.getType()).thenReturn("HTTP_REQUEST");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "remote");
    when(executor.run(any(), any(), any(), any())).thenReturn(Flux.just(out));

    final Plugin placed = nodes(executor).place("HTTP_REQUEST", PLACED, local);

    assertThat(placed).isInstanceOf(RemoteProcessor.class);
    StepVerifier.create(((ProcessorPlugin) placed).process(Flux.empty(), Map.of()))
        .expectNext(out)
        .verifyComplete();
    org.mockito.Mockito.verify(executor)
        .run(
            org.mockito.ArgumentMatchers.eq("HTTP_REQUEST"),
            org.mockito.ArgumentMatchers.eq("gpu"),
            any(),
            any());
  }

  @Test
  void placedNode_delegatesRestToLocalPlugin() {
    when(local.getType()).thenReturn("T");
    when(local.describe()).thenReturn(new PluginDescriptor("d", "u", Optional.empty()));
    when(local.getConfigSchema()).thenReturn(List.of());
    when(local.getOutputPorts()).thenReturn(List.of("out"));
    when(local.getOutputPorts(any())).thenReturn(List.of("a"));
    when(local.getDefaultTimeout()).thenReturn(Duration.ofSeconds(7));
    when(local.getDefaultTimeout(any())).thenReturn(Duration.ofSeconds(8));
    when(local.getDefaultBufferSize()).thenReturn(5);
    when(local.validateConfig(any())).thenReturn(Mono.empty());
    when(local.initialize(any())).thenReturn(Mono.empty());
    when(local.prepare(any())).thenReturn(Mono.empty());
    when(local.shutdown(any())).thenReturn(Mono.empty());
    when(local.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(local.isComputationallyHeavy(any())).thenReturn(true);
    when(local.suppressOptimizationHint(any())).thenReturn(true);
    final ProcessorPlugin placed = (ProcessorPlugin) nodes(executor).place("T", PLACED, local);

    assertThat(placed.getType()).isEqualTo("T");
    assertThat(placed.describe().description()).isEqualTo("d");
    assertThat(placed.getConfigSchema()).isEmpty();
    assertThat(placed.getOutputPorts()).containsExactly("out");
    assertThat(placed.getOutputPorts(Map.of())).containsExactly("a");
    assertThat(placed.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(7));
    assertThat(placed.getDefaultTimeout(Map.of())).isEqualTo(Duration.ofSeconds(8));
    assertThat(placed.getDefaultBufferSize()).isEqualTo(5);
    assertThat(placed.isBlocking()).isFalse();
    assertThat(placed.isComputationallyHeavy(Map.of())).isTrue();
    assertThat(placed.suppressOptimizationHint(Map.of())).isTrue();
    StepVerifier.create(
            placed
                .validateConfig(Map.of())
                .then(placed.initialize(Map.of()))
                .then(placed.prepare(Map.of()))
                .then(placed.shutdown(Map.of()))
                .then(placed.validateInContext(mock(WorkflowContext.class), Map.of())))
        .verifyComplete();
  }

  @Test
  void placementIsRefusedForTriggersLocalOnlyPluginsAndWithoutTheWorkersModule() {
    assertThatThrownBy(() -> nodes(executor).place("T", PLACED, mock(TriggerPlugin.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only for processor nodes");
    when(local.requiresControlPlane()).thenReturn(true);
    assertThatThrownBy(() -> nodes(executor).place("KV_STORE", PLACED, local))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keeps state on this server");
    when(local.requiresControlPlane()).thenReturn(false);
    assertThatThrownBy(() -> nodes(null).place("T", PLACED, local))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("-Pyukta.workers=true");
  }

  @Test
  void configFieldTypeIsAvailableToTheDelegate() {
    assertThat(ConfigField.Type.STRING).isNotNull();
  }
}
