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
class ConstantSourceTest {

  @Mock private VariableResolver resolver;
  private ConstantSource source;

  @BeforeEach
  void setUp() {
    source = new ConstantSource(resolver);
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
