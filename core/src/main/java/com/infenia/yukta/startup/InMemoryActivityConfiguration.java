// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import com.infenia.yukta.service.activity.ActivityStore;
import com.infenia.yukta.service.activity.InMemoryActivityStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The activity history in memory, imported by {@link DefaultsAutoConfiguration}. It steps aside
 * when another bean of its type exists, and when {@code yukta.session.store-type} is {@code
 * postgres}.
 */
@AutoConfiguration
public class InMemoryActivityConfiguration {

  /**
   * The activity history in memory, unless {@code yukta.session.store-type} is {@code postgres}.
   *
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean(ActivityStore.class)
  @ConditionalOnExpression(
      "!'postgres'.equalsIgnoreCase('${yukta.session.store-type:in-memory}'.trim())")
  public ActivityStore inMemoryActivityStore() {
    return new InMemoryActivityStore();
  }
}
