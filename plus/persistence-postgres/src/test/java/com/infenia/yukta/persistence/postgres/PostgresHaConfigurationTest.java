// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** Unit tests for the start check of {@link PostgresHaConfiguration}. */
class PostgresHaConfigurationTest {

  @SuppressWarnings("unchecked")
  private static ObjectProvider<NodeCheckpointStore> providerOf(final NodeCheckpointStore store) {
    final ObjectProvider<NodeCheckpointStore> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(store);
    return provider;
  }

  @Test
  void theCheckRunsAgainstTheCheckpointStoreInUse() {
    final ObjectProvider<NodeCheckpointStore> provider = providerOf(null);
    final ApplicationListener<ApplicationReadyEvent> check =
        new PostgresHaConfiguration().haCheckpointCheck(provider);

    check.onApplicationEvent(null);

    verify(provider).getIfAvailable();
    assertThat(check).isNotNull();
  }

  @Test
  void theCheckAcceptsAnyCheckpointStoreWithoutFailing() {
    final NodeCheckpointStore fileStore = mock(NodeCheckpointStore.class);
    final ApplicationListener<ApplicationReadyEvent> check =
        new PostgresHaConfiguration().haCheckpointCheck(providerOf(fileStore));

    check.onApplicationEvent(null);

    assertThat(check).isNotNull();
  }

  @Test
  void theCheckAcceptsThePostgresCheckpointStore() {
    final R2dbcNodeCheckpointStore postgresStore = mock(R2dbcNodeCheckpointStore.class);
    final ApplicationListener<ApplicationReadyEvent> check =
        new PostgresHaConfiguration().haCheckpointCheck(providerOf(postgresStore));

    check.onApplicationEvent(null);

    assertThat(check).isNotNull();
  }
}
