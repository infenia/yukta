// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.engine.EngineReads;
import java.util.HashMap;
import java.util.List;

/**
 * The {@code read} fact of a timed rule: how many owned executions are in each status, so a
 * condition can say {@code read.RUNNING > 3}. A status nobody has counts as 0.
 */
final class ReadCounts extends HashMap<String, Object> {

  private static final long serialVersionUID = 1L;

  /** Most executions one tick looks at. */
  /* default */ static final int MAX_RUNS = 200;

  /* default */ ReadCounts(final List<EngineReads.RunView> runs) {
    super();
    for (final EngineReads.RunView run : runs) {
      merge(run.status(), 1L, (a, b) -> (Long) a + (Long) b);
    }
  }

  @Override
  public boolean containsKey(final Object key) {
    return true;
  }

  @Override
  public Object get(final Object key) {
    return super.getOrDefault(key, 0L);
  }
}
