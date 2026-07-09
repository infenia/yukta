// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.MessageMapper;
import java.util.Map;

/** Example MessageMapper that maps between a generic Message and a Map domain object. */
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class MapMessageMapper implements MessageMapper<Object, Map<String, Object>> {

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> toDomain(final Message<Object> message) {
    if (message.getPayload() instanceof Map) {
      return (Map<String, Object>) message.getPayload();
    }
    return Map.of("value", message.getPayload());
  }

  @Override
  public Message<Object> fromDomain(final Map<String, Object> domain, final Message<?> original) {
    return original.withPayload(domain);
  }
}
