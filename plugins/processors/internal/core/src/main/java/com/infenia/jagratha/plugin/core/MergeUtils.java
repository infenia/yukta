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
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Utility for merging messages and payloads. */
public final class MergeUtils {

  private MergeUtils() {
    // Utility class
  }

  /**
   * Merge objects from multiple messages based on the given order.
   *
   * @param ancestors the expected order of ancestors
   * @param messages the map of collected messages
   * @return the merged object
   */
  public static Object mergeObjects(
      final List<String> ancestors, final Map<String, Message> messages) {
    final Map<String, Object> result = new ConcurrentHashMap<>();
    final List<String> order =
        (ancestors != null && !ancestors.isEmpty())
            ? ancestors
            : new ArrayList<>(messages.keySet());
    for (final String sourceId : order) {
      final Message msg = messages.get(sourceId);
      if (msg != null && msg.payload() instanceof Map) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> payloadMap = (Map<String, Object>) msg.payload();
        deepMerge(result, payloadMap);
      }
    }
    return result;
  }

  /**
   * Deep merge two maps.
   *
   * @param target the target map
   * @param source the source map
   */
  @SuppressWarnings({"unchecked", "PMD.AvoidInstantiatingObjectsInLoops"})
  public static void deepMerge(final Map<String, Object> target, final Map<String, Object> source) {
    for (final Map.Entry<String, Object> entry : source.entrySet()) {
      final String key = entry.getKey();
      final Object sVal = entry.getValue();
      final Object tVal = target.get(key);
      if (sVal instanceof Map && tVal instanceof Map) {
        // Defensive copy to handle immutable maps
        final Map<String, Object> targetMap = (Map<String, Object>) tVal;
        final Map<String, Object> mutableTargetMap;
        try {
          mutableTargetMap = targetMap;
          mutableTargetMap.putAll(Map.of()); // Test mutability
        } catch (final UnsupportedOperationException e) {
          final Map<String, Object> newTargetMap = new ConcurrentHashMap<>(targetMap);
          target.put(key, newTargetMap);
          deepMerge(newTargetMap, (Map<String, Object>) sVal);
          continue;
        }
        deepMerge(mutableTargetMap, (Map<String, Object>) sVal);
      } else {
        target.put(key, sVal);
      }
    }
  }
}
