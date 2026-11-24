// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import java.util.function.IntFunction;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/**
 * Runs a large delete as short statements of at most {@link #SIZE} rows each, so a big backlog (a
 * long downtime, a lowered retention) never becomes one long transaction that holds locks, bloats
 * the WAL or runs into the statement timeout and rolls back without progress.
 */
@UtilityClass
class Batches {

  /** Rows one statement deletes at most. */
  /* default */ static final int SIZE = 5_000;

  /**
   * Runs {@code batch} until one deletes fewer than {@link #SIZE} rows.
   *
   * @param batch deletes at most the given number of rows and answers how many it deleted
   * @return how many rows were deleted in all
   */
  /* default */ static Mono<Long> repeat(final IntFunction<Mono<Long>> batch) {
    return batch
        .apply(SIZE)
        .expand(deleted -> deleted < SIZE ? Mono.empty() : batch.apply(SIZE))
        .reduce(0L, Long::sum);
  }
}
