// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.activity;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The history behind the activity feed, so a page can show what happened before it was opened. In
 * memory (a bounded ring) in Community, PostgreSQL in Plus when {@code
 * yukta.session.store-type=postgres}. Log lines are not kept: the log store holds them.
 */
public interface ActivityStore {

  /**
   * Keeps entries, oldest first. The store numbers them; the {@code seq} given is ignored.
   *
   * @param entries the entries
   * @return completes when kept
   */
  Mono<Void> append(List<ActivityEntry> entries);

  /**
   * The newest entries, newest first.
   *
   * @param types only these kinds, or empty for all
   * @param severities only these severities, or empty for all
   * @param before only entries with a lower {@code seq}, or null for the newest
   * @param limit most entries
   * @return the entries
   */
  Flux<ActivityEntry> newest(
      Set<String> types, Set<String> severities, @Nullable Long before, int limit);
}
