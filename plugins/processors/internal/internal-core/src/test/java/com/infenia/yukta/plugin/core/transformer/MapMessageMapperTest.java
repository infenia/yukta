// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class MapMessageMapperTest {

  private final MapMessageMapper mapper = new MapMessageMapper();

  @Test
  void mapPayloadIsReturnedAsIs() {
    final Map<String, Object> payload = Map.of("a", 1);
    final Message<Object> m = DefaultMessage.create(UUID.randomUUID(), (Object) payload);
    assertThat(mapper.toDomain(m)).isSameAs(payload);
  }

  @Test
  void scalarPayloadIsWrapped() {
    final Message<Object> m = DefaultMessage.create(UUID.randomUUID(), (Object) "x");
    assertThat(mapper.toDomain(m)).isEqualTo(Map.of("value", "x"));
  }

  @Test
  void fromDomainReplacesPayload() {
    final Message<Object> m = DefaultMessage.create(UUID.randomUUID(), (Object) "x");
    assertThat(mapper.fromDomain(Map.of("a", 1), m).payload()).isEqualTo(Map.of("a", 1));
  }
}
