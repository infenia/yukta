// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.gateway.PluginLookup;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

/** Finding the processor a loop node runs. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "unchecked"})
class LoopTargetTest {

  /** The registry the loop asks. */
  private final PluginLookup registry = mock(PluginLookup.class);

  /** Hands out the registry. */
  private final ObjectProvider<PluginLookup> provider = mock(ObjectProvider.class);

  @Test
  void processorInTheRegistryIsFound() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    when(registry.find("p")).thenReturn(Optional.<Plugin>of(processor));

    StepVerifier.create(LoopTarget.resolve(provider, Map.of("targetPluginId", "p")))
        .expectNext(processor)
        .verifyComplete();
  }

  @Test
  void missingIdUnknownIdAndNoRegistryAreNotFound() {
    when(provider.getIfAvailable()).thenReturn(registry);
    StepVerifier.create(LoopTarget.resolve(provider, Map.of()))
        .expectErrorMessage("Target plugin not found: null")
        .verify();
    StepVerifier.create(LoopTarget.resolve(provider, Map.of("targetPluginId", "x")))
        .expectErrorMessage("Target plugin not found: x")
        .verify();

    final ObjectProvider<PluginLookup> none = mock(ObjectProvider.class);
    StepVerifier.create(LoopTarget.resolve(none, Map.of("targetPluginId", "p")))
        .expectErrorMessage("Target plugin not found: p")
        .verify();
  }

  @Test
  void pluginThatIsNotProcessorIsRefused() {
    when(provider.getIfAvailable()).thenReturn(registry);
    when(registry.find("t")).thenReturn(Optional.<Plugin>of(mock(Plugin.class)));

    StepVerifier.create(LoopTarget.resolve(provider, Map.of("targetPluginId", "t")))
        .expectErrorMessage("Target plugin must be a ProcessorPlugin: t")
        .verify();
  }

  @Test
  void theTargetConfigDefaultsToEmpty() {
    assertThat(LoopTarget.configOf(Map.of())).isEmpty();
    assertThat(LoopTarget.configOf(Map.of("targetConfig", Map.of("k", "v"))))
        .isEqualTo(Map.of("k", "v"));
  }
}
