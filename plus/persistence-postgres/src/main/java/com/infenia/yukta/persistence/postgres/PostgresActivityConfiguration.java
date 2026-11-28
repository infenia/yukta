// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.activity.ActivityStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

/** The activity history in PostgreSQL, imported by {@link PostgresPersistenceAutoConfiguration}. */
@Configuration(proxyBeanMethods = false)
public class PostgresActivityConfiguration {

  /**
   * The activity history, kept in PostgreSQL and trimmed by age and size.
   *
   * @param client runs the statements
   * @param maxRows rows kept
   * @param maxDays days a row is kept
   * @return the store
   */
  @Bean
  public ActivityStore activityStore(
      final DatabaseClient client,
      @Value("${yukta.activity.max-rows:100000}") final int maxRows,
      @Value("${yukta.activity.max-days:14}") final int maxDays) {
    return new R2dbcActivityStore(client, maxRows, Duration.ofDays(maxDays));
  }
}
