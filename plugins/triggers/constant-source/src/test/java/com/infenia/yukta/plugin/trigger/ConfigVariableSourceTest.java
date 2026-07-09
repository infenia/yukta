// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.infenia.yukta.util.VariableResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ConfigVariableSourceTest {

  @Mock private VariableResolver resolver;
  private ConfigVariableSource source;

  @BeforeEach
  void setUp() {
    source = new ConfigVariableSource(resolver);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testStartWithPayloadTarget() {
    when(resolver.isStatic(any())).thenReturn(false);
    when(resolver.resolve("val")).thenReturn(Mono.just("resolved-val"));

    final Map<String, Object> config =
        Map.of("target", "PAYLOAD", "variables", Map.of("key", "val"));

    source.initialize(config).block();

    StepVerifier.create(source.start(config).contextWrite(ctx -> ctx.put("payload", Map.of())))
        .assertNext(
            message -> {
              assertTrue(message.getPayload() instanceof Map);
              Map<String, Object> payload = (Map<String, Object>) message.getPayload();
              assertEquals("resolved-val", payload.get("key"));
              assertTrue(message.getMetadata().isEmpty());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testStartWithMetadataTarget() {
    when(resolver.isStatic(any())).thenReturn(false);
    when(resolver.resolve("val")).thenReturn(Mono.just("resolved-val"));

    final Map<String, Object> config =
        Map.of("target", "METADATA", "variables", Map.of("key", "val"));

    source.initialize(config).block();

    StepVerifier.create(source.start(config).contextWrite(ctx -> ctx.put("payload", Map.of())))
        .assertNext(
            message -> {
              assertEquals("resolved-val", message.getMetadata().get("key"));
              assertTrue(((Map) message.getPayload()).isEmpty());
            })
        .verifyComplete();
  }
}
