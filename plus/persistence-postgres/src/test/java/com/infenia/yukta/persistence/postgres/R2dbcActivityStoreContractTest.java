// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plus.testkit.ActivityStoreContract;
import com.infenia.yukta.service.activity.ActivityEntry;
import com.infenia.yukta.service.activity.ActivityStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The shared activity store contract, run against a real PostgreSQL. */
@NoArgsConstructor
@Testcontainers(disabledWithoutDocker = true)
class R2dbcActivityStoreContractTest extends ActivityStoreContract {

  @Override
  protected ActivityStore create() {
    final DatabaseClient db = PostgresTestSupport.prepared();
    db.sql("DELETE FROM activity").fetch().rowsUpdated().block();
    return new R2dbcActivityStore(db, 100_000, Duration.ofDays(14));
  }

  @Test
  void theOldRowsAreTrimmedOnceEnoughEntriesWereAppended() {
    final DatabaseClient db = PostgresTestSupport.prepared();
    db.sql("DELETE FROM activity").fetch().rowsUpdated().block();
    final ActivityStore store = new R2dbcActivityStore(db, 2, Duration.ofDays(14));
    final List<ActivityEntry> batch =
        IntStream.rangeClosed(1, 1_000)
            .mapToObj(n -> new ActivityEntry(0, Instant.now(), "Tick", "INFO", "e" + n, "{}"))
            .toList();

    store.append(batch).block();

    assertThat(store.newest(Set.of(), Set.of(), null, 10).collectList().block())
        .extracting(ActivityEntry::executionId)
        .containsExactly("e1000", "e999");
  }
}
