// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Gives each branch of a fan-out its own mutable containers, so a plugin that edits its payload
 * cannot be seen by a sibling branch.
 *
 * <p>Maps, lists, sets and byte arrays are copied recursively; anything else (strings, numbers,
 * records, plugin POJOs) is passed on as is.
 */
@UtilityClass
final class PayloadCopy {

  /** Returns the message with its payload and header values copied. */
  /* default */ static Message<?> copyOf(final Message<?> msg) {
    return msg.withPayload(copy(msg.payload())).withMetadata(copyMap(msg.metadata()));
  }

  private static Object copy(final Object value) {
    return switch (value) {
      case Map<?, ?> map -> copyMap(map);
      case List<?> list -> copyList(list);
      case Set<?> set -> copySet(set);
      case byte[] bytes -> bytes.clone();
      case null, default -> value;
    };
  }

  @SuppressWarnings({"unchecked", "PMD.UseConcurrentHashMap"}) // map is local, never shared
  private static Map<String, Object> copyMap(final Map<?, ?> map) {
    final Map<Object, Object> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(k, copy(v)));
    return (Map<String, Object>) (Map<?, ?>) out;
  }

  private static List<Object> copyList(final List<?> list) {
    final List<Object> out = new ArrayList<>(list.size());
    list.forEach(v -> out.add(copy(v)));
    return out;
  }

  private static Set<Object> copySet(final Set<?> set) {
    final Set<Object> out = new LinkedHashSet<>();
    set.forEach(v -> out.add(copy(v)));
    return out;
  }
}
