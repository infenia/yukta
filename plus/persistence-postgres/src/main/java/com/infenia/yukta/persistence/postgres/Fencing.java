// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Refuses a write from a server that has lost the leader lease. The statement itself carries the
 * check, so it is atomic with the write: the write happens only if no lease row holds a fencing
 * number above the one this server held when it became leader. A server that never led (a standby,
 * or a single server without {@code yukta.ha.mode=postgres}) has token 0 and is not checked.
 */
@Slf4j
final class Fencing {

  /** Condition to append to a statement's WHERE; needs the {@code fence} parameter bound. */
  /* default */ static final String NOT_STALE =
      "NOT EXISTS (SELECT 1 FROM leader_lease WHERE name = 'leader' AND fencing > :fence)";

  /** Parameter name used by {@link #NOT_STALE}. */
  /* default */ static final String PARAM = "fence";

  /** Reads this server's fencing number; null when no election runs. */
  private final LongSupplier token;

  /** Counts refused writes. */
  private final Counter refusals;

  /**
   * Creates the check.
   *
   * @param token reads this server's fencing number, null for no check
   * @param meters where {@code yukta.fencing.refused} is counted
   * @param store which store the counter is for
   */
  /* default */ Fencing(final LongSupplier token, final MeterRegistry meters, final String store) {
    this.token = token;
    this.refusals =
        Counter.builder("yukta.fencing.refused")
            .description("Writes refused because another server took over the leader lease")
            .tag("store", store)
            .register(meters);
  }

  /**
   * The fencing number to bind, never stale when this server has not led.
   *
   * @return the number held, or the largest value if none
   */
  /* default */ long bound() {
    final long held = token == null ? 0 : token.getAsLong();
    return held > 0 ? held : Long.MAX_VALUE;
  }

  /**
   * Binds the parameter of {@link #NOT_STALE}.
   *
   * @param spec the statement
   * @return the statement with the parameter bound
   */
  /* default */ DatabaseClient.GenericExecuteSpec bind(
      final DatabaseClient.GenericExecuteSpec spec) {
    return spec.bind(PARAM, bound());
  }

  /**
   * Counts and logs writes that were refused.
   *
   * @param count how many writes
   */
  /* default */ void refused(final int count) {
    refusals.increment(count);
    log.atError()
        .addKeyValue("writes", count)
        .log("Write refused: another server holds the leader lease with a newer fencing number");
  }

  /**
   * Reads the fencing number of an election.
   *
   * @param election the election
   * @return a supplier of its current fencing number
   */
  /* default */ static LongSupplier tokenOf(final R2dbcLeaderElection election) {
    return election::getFencingToken;
  }
}
