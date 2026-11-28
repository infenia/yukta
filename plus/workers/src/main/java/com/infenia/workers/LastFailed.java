// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The worker that failed the last attempt of each node, so that a retry goes to another one. */
final class LastFailed {

  /** The failed worker by execution and node. */
  private final Map<String, String> workers = new ConcurrentHashMap<>();

  /* default */ void failed(final String executionId, final String nodeId, final String workerId) {
    workers.put(key(executionId, nodeId), workerId);
  }

  /* default */ void succeeded(final String executionId, final String nodeId) {
    workers.remove(key(executionId, nodeId));
  }

  /* default */ @Nullable String workerOf(final String executionId, final String nodeId) {
    return workers.get(key(executionId, nodeId));
  }

  /** Keeps the order, but moves the elements of the avoided worker to the end. */
  /* default */ static <T> List<T> avoiding(
      final List<T> ordered, final Function<T, String> idOf, @Nullable final String avoid) {
    final List<T> result = new ArrayList<>(ordered.size());
    final List<T> avoided = new ArrayList<>();
    for (final T element : ordered) {
      if (idOf.apply(element).equals(avoid)) {
        avoided.add(element);
      } else {
        result.add(element);
      }
    }
    result.addAll(avoided);
    return result;
  }

  private static String key(final String executionId, final String nodeId) {
    return executionId + '/' + nodeId;
  }
}
