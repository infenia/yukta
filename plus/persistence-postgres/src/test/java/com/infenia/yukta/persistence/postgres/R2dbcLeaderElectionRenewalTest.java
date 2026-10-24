// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

/** The renewal loop of {@link R2dbcLeaderElection} without a database. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.UnitTestShouldIncludeAssert"})
class R2dbcLeaderElectionRenewalTest {

  @Test
  void renewalThatThrowsWhileBuilt_doesNotEndTheLoop() {
    final DatabaseClient client = mock(DatabaseClient.class);
    when(client.sql(anyString()))
        .thenThrow(new IllegalStateException("cannot build the statement"));
    final R2dbcLeaderElection election =
        new R2dbcLeaderElection(client, "x", Duration.ofMillis(300), new SimpleMeterRegistry());

    election.start();

    // the interval is a third of the lease, 100 ms: the loop keeps trying after the first failure
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> verify(client, atLeast(3)).sql(anyString()));
    election.abandon();
  }
}
