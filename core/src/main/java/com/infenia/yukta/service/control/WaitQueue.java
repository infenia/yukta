// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Waiting starts by priority (highest first, oldest first within one); the lock guards it. */
public final class WaitQueue {
  private final ConcurrentNavigableMap<Integer, Set<Waiter>> byPriority =
      new ConcurrentSkipListMap<>(Comparator.reverseOrder());
  private final Map<String, Integer> perKey = new ConcurrentHashMap<>();
  private final Map<String, Waiter> waitersById = new ConcurrentHashMap<>();
  private int total;

  /**
   * Number of waiting starts.
   *
   * @return the count
   */
  public int size() {
    return total;
  }

  /**
   * Number of waiting starts of one workflow.
   *
   * @param key the workflow key
   * @return the count
   */
  public int count(final String key) {
    return perKey.getOrDefault(key, 0);
  }

  /**
   * The waiting start of an execution.
   *
   * @param executionId the execution
   * @return the waiter, or null if it is not waiting
   */
  @Nullable
  public Waiter byExecution(final String executionId) {
    return waitersById.get(executionId);
  }

  /**
   * The waiting starts grouped by priority, highest first.
   *
   * @return the groups
   */
  public Collection<Set<Waiter>> groups() {
    return byPriority.values();
  }

  /**
   * Adds a waiting start at the end of its priority.
   *
   * @param waiter the start
   */
  public void add(final Waiter waiter) {
    byPriority.computeIfAbsent(waiter.priority().get(), _ -> new LinkedHashSet<>()).add(waiter);
    perKey.merge(waiter.key(), 1, Integer::sum);
    final String executionId = waiter.executionId();
    if (executionId != null) {
      waitersById.put(executionId, waiter);
    }
    total++;
  }

  /**
   * Removes a waiter; call before its priority changes.
   *
   * @param waiter the start
   * @return true if it was waiting
   */
  public boolean remove(final Waiter waiter) {
    final int priority = waiter.priority().get();
    final Set<Waiter> group = byPriority.get(priority);
    if (group == null || !group.remove(waiter)) {
      return false;
    }
    if (group.isEmpty()) {
      byPriority.remove(priority);
    }
    perKey.computeIfPresent(waiter.key(), (_, count) -> count > 1 ? count - 1 : null);
    final String executionId = waiter.executionId();
    if (executionId != null) {
      waitersById.remove(executionId, waiter);
    }
    total--;
    return true;
  }

  /**
   * Removes every waiting start, handing each to the action.
   *
   * @param action what to do with each start
   */
  public void drain(final Consumer<Waiter> action) {
    byPriority.values().forEach(group -> group.forEach(action));
    byPriority.clear();
    perKey.clear();
    waitersById.clear();
    total = 0;
  }
}
