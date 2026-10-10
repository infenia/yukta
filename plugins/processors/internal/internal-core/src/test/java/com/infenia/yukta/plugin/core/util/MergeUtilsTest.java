// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.CommentRequired", "unchecked"})
class MergeUtilsTest {

  private static Message<?> msg(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void mergesInAncestorOrderLastWins() {
    final Map<String, Message<?>> in = new LinkedHashMap<>();
    in.put("a", msg(Map.of("k", 1, "only-a", true)));
    in.put("b", msg(Map.of("k", 2)));

    final Map<String, Object> out =
        (Map<String, Object>) MergeUtils.mergeObjects(List.of("b", "a"), in);

    assertThat(out).containsEntry("k", 1).containsEntry("only-a", true);
  }

  @Test
  void emptyOrNullAncestorsUseMessageKeys_skippingMissingAndNonMap() {
    final Map<String, Message<?>> in = new LinkedHashMap<>();
    in.put("a", msg(Map.of("x", 1)));
    in.put("s", msg("not a map"));

    assertThat((Map<String, Object>) MergeUtils.mergeObjects(null, in)).containsOnlyKeys("x");
    assertThat((Map<String, Object>) MergeUtils.mergeObjects(List.of(), in)).containsOnlyKeys("x");
    assertThat((Map<String, Object>) MergeUtils.mergeObjects(List.of("ghost", "s", "a"), in))
        .containsOnlyKeys("x");
  }

  @Test
  void deepMergesNestedMutableAndImmutableMaps() {
    final Map<String, Object> mutableInner = new HashMap<>(Map.of("a", 1));
    final Map<String, Object> target = new HashMap<>();
    target.put("m", mutableInner);
    target.put("i", Map.of("a", 1));
    target.put("scalar", 1);

    MergeUtils.deepMerge(
        target, Map.of("m", Map.of("b", 2), "i", Map.of("b", 2), "scalar", Map.of("n", 1)));

    assertThat((Map<String, Object>) target.get("m")).containsEntry("a", 1).containsEntry("b", 2);
    assertThat((Map<String, Object>) target.get("i")).containsEntry("a", 1).containsEntry("b", 2);
    assertThat(target.get("scalar")).isEqualTo(Map.of("n", 1));
  }
}
