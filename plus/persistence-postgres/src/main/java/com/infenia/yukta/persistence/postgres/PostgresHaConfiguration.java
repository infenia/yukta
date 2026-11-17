// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

/** The beans needed only with {@code yukta.ha.mode=postgres}: the lease and its start check. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "yukta.ha.mode", havingValue = "postgres")
public class PostgresHaConfiguration {

  /**
   * Leader election through a lease row.
   *
   * @param client runs the statements
   * @param instanceId this server's id in the lease row (random if not set)
   * @param lease how long a renewal keeps the lease
   * @param meters where the {@code yukta.leader} gauge is registered
   * @return the election
   */
  @Bean
  public R2dbcLeaderElection leaderElection(
      final DatabaseClient client,
      @Value("${yukta.ha.instance-id:#{T(java.util.UUID).randomUUID().toString()}}")
          final String instanceId,
      @Value("${yukta.ha.lease:15s}") final java.time.Duration lease,
      final io.micrometer.core.instrument.MeterRegistry meters) {
    return new R2dbcLeaderElection(client, instanceId, lease, meters);
  }

  /**
   * Warns at start when a standby can take over runs but not continue them: with {@code
   * yukta.ha.mode=postgres} the node outputs must be in PostgreSQL too, or a resume after a
   * takeover runs every node again.
   *
   * @param checkpoints the checkpoint store in use
   * @return the listener
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> haCheckpointCheck(
      final ObjectProvider<NodeCheckpointStore> checkpoints) {
    return _ -> {
      final NodeCheckpointStore store = checkpoints.getIfAvailable();
      if (!(store instanceof R2dbcNodeCheckpointStore)) {
        log.atWarn()
            .addKeyValue("checkpoints", store == null ? "none" : store.getClass().getSimpleName())
            .log(
                "yukta.ha.mode=postgres without yukta.restart.store-type=postgres: a server that"
                    + " takes over cannot replay the output of finished nodes, so a resumed run"
                    + " runs them again");
      }
    };
  }
}
