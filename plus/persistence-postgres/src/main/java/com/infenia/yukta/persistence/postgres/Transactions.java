// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import lombok.experimental.UtilityClass;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Runs a multi-statement operation as one transaction; inside another one it joins it. */
@UtilityClass
class Transactions {

  /**
   * Runs {@code work} in a transaction: all its statements commit together, or none do.
   *
   * @param client the client whose connection factory holds the data
   * @param work the statements, as one publisher
   * @param <T> what the work produces
   * @return the work, now transactional
   */
  /* default */ static <T> Mono<T> inTransaction(final DatabaseClient client, final Mono<T> work) {
    return TransactionalOperator.create(new R2dbcTransactionManager(client.getConnectionFactory()))
        .transactional(work);
  }
}
