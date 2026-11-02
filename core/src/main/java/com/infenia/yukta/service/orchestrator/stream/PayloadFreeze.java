// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Makes a message safe to share between the branches of a fan-out: payload and header containers
 * become unmodifiable, once per message instead of once per edge.
 *
 * <p>Payloads are immutable once a node has emitted them. Maps, lists and sets are frozen
 * recursively (order and null values kept); a branch that wants to edit one copies it first.
 * Anything else (strings, numbers, records, plugin POJOs, byte arrays) is shared as is, so it must
 * not be modified.
 */
@UtilityClass
final class PayloadFreeze {

  /** Returns the message with its payload and header containers frozen. */
  /* default */ static Message<?> freezeOf(final Message<?> msg) {
    return msg.withPayload(freeze(msg.payload())).withMetadata(freezeMap(msg.metadata()));
  }

  private static Object freeze(final Object value) {
    return switch (value) {
      case Map<?, ?> map -> freezeMap(map);
      case List<?> list -> freezeList(list);
      case Set<?> set -> freezeSet(set);
      case null, default -> value;
    };
  }

  @SuppressWarnings({"unchecked", "PMD.UseConcurrentHashMap"}) // map is local until frozen
  private static Map<String, Object> freezeMap(final Map<?, ?> map) {
    final Map<Object, Object> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(k, freeze(v)));
    return (Map<String, Object>) (Map<?, ?>) Collections.unmodifiableMap(out);
  }

  private static List<Object> freezeList(final List<?> list) {
    final List<Object> out = new ArrayList<>(list.size());
    list.forEach(v -> out.add(freeze(v)));
    return Collections.unmodifiableList(out);
  }

  private static Set<Object> freezeSet(final Set<?> set) {
    final Set<Object> out = new LinkedHashSet<>();
    set.forEach(v -> out.add(freeze(v)));
    return Collections.unmodifiableSet(out);
  }
}
