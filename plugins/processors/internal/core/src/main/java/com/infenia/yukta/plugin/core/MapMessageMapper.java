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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
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
