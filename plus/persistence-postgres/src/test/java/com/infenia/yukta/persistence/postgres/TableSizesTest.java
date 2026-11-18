// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;

/** Unit tests for {@link TableSizes}, with the database client mocked. */
class TableSizesTest {

  /** The table used in the tests. */
  private static final String EXECUTION_LOG = "execution_log";

  /** Stubs the query chain of the client to answer with the given flux. */
  private static DatabaseClient clientAnswering(final Flux<Map.Entry<String, Long>> answer) {
    final DatabaseClient client = Mockito.mock(DatabaseClient.class);
    final DatabaseClient.GenericExecuteSpec spec =
        Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
    final RowsFetchSpec<Map.Entry<String, Long>> rows = Mockito.mock(RowsFetchSpec.class);
    Mockito.when(client.sql(ArgumentMatchers.anyString())).thenReturn(spec);
    Mockito.when(spec.bind(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(spec);
    Mockito.doReturn(rows).when(spec).map(ArgumentMatchers.any(Function.class));
    Mockito.doReturn(answer).when(rows).all();
    return client;
  }

  @Test
  void bytesReadsTheSizeOnceAndAnswersFromTheLastKnownValueWhileFresh() {
    final DatabaseClient client = clientAnswering(Flux.just(Map.entry(EXECUTION_LOG, 42L)));
    final TableSizes sizes = new TableSizes(client, List.of(EXECUTION_LOG));

    assertThat(sizes.bytes(EXECUTION_LOG)).isEqualTo(42L);
    assertThat(sizes.bytes(EXECUTION_LOG)).isEqualTo(42L);

    Mockito.verify(client, Mockito.times(1)).sql(ArgumentMatchers.anyString());
  }

  @Test
  void bytesKeepsTheLastKnownSizeWhenTheReadFails() {
    final DatabaseClient client =
        clientAnswering(Flux.error(new IllegalStateException("database down")));
    final TableSizes sizes = new TableSizes(client, List.of(EXECUTION_LOG));

    assertThat(sizes.bytes(EXECUTION_LOG)).isZero();
    assertThat(sizes.bytes(EXECUTION_LOG)).isZero();

    Mockito.verify(client, Mockito.times(1)).sql(ArgumentMatchers.anyString());
  }

  @Test
  void bytesDoesNotStartAnotherReadWhileOneIsGoing() {
    final DatabaseClient client = clientAnswering(Flux.never());
    final AtomicLong clock = new AtomicLong();
    final TableSizes sizes = new TableSizes(client, List.of(EXECUTION_LOG), clock::get);
    sizes.bytes(EXECUTION_LOG);
    // age the last ask past the limit, so only the running read keeps a new one away
    clock.addAndGet(Duration.ofMinutes(2).toNanos());

    assertThat(sizes.bytes(EXECUTION_LOG)).isZero();

    Mockito.verify(client, Mockito.times(1)).sql(ArgumentMatchers.anyString());
  }

  @Test
  void registerExposesOneGaugePerTableThatReadsTheSize() {
    final DatabaseClient client = clientAnswering(Flux.just(Map.entry(EXECUTION_LOG, 7L)));
    final TableSizes sizes = new TableSizes(client, List.of(EXECUTION_LOG));
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    sizes.register(meters);

    assertThat(meters.get("yukta.store.table.bytes").tag("table", EXECUTION_LOG).gauge().value())
        .isEqualTo(7.0);
  }
}
