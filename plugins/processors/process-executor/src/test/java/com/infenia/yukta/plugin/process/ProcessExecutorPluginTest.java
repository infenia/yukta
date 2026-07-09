// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.util.VariableResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessExecutorPluginTest {

  private ProcessExecutorPlugin plugin;
  @Mock private ProcessExecutorGateway gateway;
  @Mock private VariableResolver resolver;

  @BeforeEach
  void setUp() {
    plugin = new ProcessExecutorPlugin(gateway, resolver);
    // Generic resolver mock to handle all SpEL resolutions by returning the input
    when(resolver.resolve(any()))
        .thenAnswer(
            invocation -> {
              Object arg = invocation.getArgument(0);
              return Mono.justOrEmpty(arg);
            });
  }

  @Test
  void testGetType() {
    assertEquals("PROCESS_EXECUTOR", plugin.getType());
  }

  @Test
  void testGetDescription() {
    assertNotNull(plugin.getDescription());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testProcessPayloadOutput() {
    final Map<String, Object> config = Map.of("command", List.of("echo", "hello"));
    final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "input");

    when(gateway.executeStream(any(List.class), any(), anyLong(), any(Map.class), anyBoolean()))
        .thenReturn(Flux.just("hello"));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .assertNext(
            message -> {
              assertEquals("hello", message.getPayload());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testProcessMetadataOutput() {
    final Map<String, Object> config =
        Map.of("command", List.of("echo", "hello"), "outputTarget", "METADATA");
    final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "input");

    when(gateway.executeStream(any(List.class), any(), anyLong(), any(Map.class), anyBoolean()))
        .thenReturn(Flux.just("hello"));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .assertNext(
            message -> {
              assertEquals("input", message.getPayload());
              assertEquals("hello", message.getMetadata().get("process.output"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testProcessStreamingOutput() {
    final Map<String, Object> config =
        Map.of("command", List.of("echo", "hello"), "streamOutput", true);
    final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "input");

    when(gateway.executeStream(any(List.class), any(), anyLong(), any(Map.class), anyBoolean()))
        .thenReturn(Flux.just("line1", "line2"));

    StepVerifier.create(plugin.process(Flux.just(input), config))
        .expectNextMatches(m -> "line1".equals(m.getPayload()))
        .expectNextMatches(m -> "line2".equals(m.getPayload()))
        .verifyComplete();
  }

  @Test
  void testValidateConfigMissingCommand() {
    StepVerifier.create(plugin.validateConfig(Map.of()))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void testAutoConfiguration() {
    ProcessExecutorAutoConfiguration autoConfig = new ProcessExecutorAutoConfiguration();
    assertNotNull(autoConfig.processExecutorPlugin(gateway, resolver));
  }
}
