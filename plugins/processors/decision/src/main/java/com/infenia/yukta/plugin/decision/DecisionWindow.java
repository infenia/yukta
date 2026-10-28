// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.util.context.ContextView;

/**
 * The recent items a {@code DEDUPE} or {@code ANOMALY} node compares against. With {@code persist:
 * none} (the default) every run of a node has its own window. With {@code execution} or {@code
 * session} the window is kept in memory under the execution or session and {@code windowKey}
 * (default the node id), so nodes with one key share it and, for {@code session}, a later execution
 * of the session sees what an earlier one saw. At most {@value #MAX_WINDOWS} shared windows are
 * kept, least recently used first out; they are lost on restart.
 *
 * @param <T> what is remembered
 */
@SuppressWarnings({
  "PMD.AtLeastOneConstructor",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.AvoidSynchronizedAtMethodLevel",
  "PMD.AvoidSynchronizedStatement",
  "PMD.CommentDefaultAccessModifier",
  "PMD.OnlyOneReturn",
  "PMD.UseConcurrentHashMap"
})
final class DecisionWindow<T> {

  /** Most shared windows kept at once. */
  private static final int MAX_WINDOWS = 1000;

  /** Config key: how long the window lives. */
  private static final String PERSIST = "persist";

  /** Config key: which nodes share a window. */
  private static final String WINDOW_KEY = "windowKey";

  /** Value of {@code persist} that gives each run its own window. */
  private static final String NONE = "none";

  /** Shared windows by plugin type, scope and key, in access order. */
  private static final Map<String, DecisionWindow<?>> SHARED =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, DecisionWindow<?>> eldest) {
          return size() > MAX_WINDOWS;
        }
      };

  /** Oldest first; guarded by {@code this}. */
  private final Deque<T> items = new ArrayDeque<>();

  /** The settings every windowed plugin adds. */
  static List<ConfigField> fields() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(
        ConfigField.choice(
            PERSIST,
            false,
            NONE,
            "none: one window per run of the node; execution: shared by nodes with one windowKey"
                + " in the execution; session: shared across the executions of the session",
            NONE,
            "execution",
            "session"));
    fields.add(
        ConfigField.optional(
            WINDOW_KEY,
            ConfigField.Type.STRING,
            null,
            "With persist execution or session: nodes with one key share a window (default the"
                + " node id)"));
    return fields;
  }

  /**
   * Checks the {@code persist} setting.
   *
   * @param config the node configuration
   * @throws IllegalArgumentException if it is none of none, execution, session
   */
  static void check(final Map<String, Object> config) {
    final String persist = persist(config);
    if (!NONE.equals(persist) && !"execution".equals(persist) && !"session".equals(persist)) {
      throw new IllegalArgumentException(PERSIST + " must be none, execution or session");
    }
  }

  /**
   * The window a run of a node uses.
   *
   * @param type the plugin type, so DEDUPE and ANOMALY never share
   * @param config the node configuration
   * @param context the run's context (execution, session and node ids)
   * @param <T> what is remembered
   * @return a new window, or the shared one for the configured scope and key
   */
  @SuppressWarnings("unchecked")
  static <T> DecisionWindow<T> open(
      final String type, final Map<String, Object> config, final ContextView context) {
    final String persist = persist(config);
    if (NONE.equals(persist)) {
      return new DecisionWindow<>();
    }
    final String scope =
        "session".equals(persist)
            ? context.getOrDefault("sessionId", "unknown")
            : context.getOrDefault("executionId", "unknown");
    final Object configured = config.get(WINDOW_KEY);
    final String key =
        configured == null || configured.toString().isBlank()
            ? context.getOrDefault("nodeId", "unknown")
            : configured.toString();
    synchronized (SHARED) {
      return (DecisionWindow<T>)
          SHARED.computeIfAbsent(
              type + '\0' + persist + '\0' + scope + '\0' + key, _ -> new DecisionWindow<>());
    }
  }

  private static String persist(final Map<String, Object> config) {
    final Object value = config.get(PERSIST);
    return value == null || value.toString().isBlank() ? NONE : value.toString().trim();
  }

  /**
   * The items, oldest first.
   *
   * @return a copy
   */
  synchronized List<T> snapshot() {
    return List.copyOf(items);
  }

  /**
   * Remembers an item and forgets the oldest ones beyond {@code max}.
   *
   * @param item the item
   * @param max the most items kept
   */
  synchronized void add(final T item, final int max) {
    items.addLast(item);
    while (items.size() > max) {
      items.removeFirst();
    }
  }
}
