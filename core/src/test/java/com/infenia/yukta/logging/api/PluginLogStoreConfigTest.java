// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Test suite for {@link PluginLogStoreConfig}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CommentRequired", "PMD.LawOfDemeter"})
class PluginLogStoreConfigTest {

  private PluginLogStoreConfig config;

  @BeforeEach
  void setUp() {
    config = new PluginLogStoreConfig();
  }

  @Test
  void testDefaultRetentionPeriod() {
    final Duration retention = config.getEffectiveRetention();
    assertThat(retention).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void testGetMaxRetentionMinutes() {
    assertThat(config.getMaxRetentionMinutes()).isEqualTo(1440);
  }

  @Test
  void testEffectiveRetentionCapsAtMaximum() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(2880);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofDays(1));
  }

  @Test
  void testEffectiveRetentionRespectsBelowMaximum() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(60);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofMinutes(60));
  }

  @Test
  void testRetentionGetterReturnsDefensiveCopy() {
    final PluginLogStoreConfig.Retention retention1 = config.getRetention();
    final PluginLogStoreConfig.Retention retention2 = config.getRetention();

    assertThat(retention1).isNotSameAs(retention2);
    assertThat(retention1.getDefaultPeriodMinutes())
        .isEqualTo(retention2.getDefaultPeriodMinutes());
  }

  @Test
  void testRetentionSetterCreatesDefensiveCopy() {
    final PluginLogStoreConfig.Retention original = new PluginLogStoreConfig.Retention();
    original.setDefaultPeriodMinutes(120);

    config.setRetention(original);
    original.setDefaultPeriodMinutes(60);

    final PluginLogStoreConfig.Retention stored = config.getRetention();
    assertThat(stored.getDefaultPeriodMinutes()).isEqualTo(120);
  }

  @Test
  void testRetentionSetterWithMaxValue() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(1440);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofDays(1));
  }

  @Test
  void testRetentionSetterWithMinValue() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(1);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void testRetentionSetterWithZeroClampsToMinimum() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(0);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void testRetentionSetterWithNegativeValueClampsToMinimum() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(-10);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void testRetentionNestedClassGettersAndSetters() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    assertThat(retention.getDefaultPeriodMinutes()).isEqualTo(30);

    retention.setDefaultPeriodMinutes(100);
    assertThat(retention.getDefaultPeriodMinutes()).isEqualTo(100);
  }

  @Test
  void testMultipleRetentionUpdates() {
    final PluginLogStoreConfig.Retention retention1 = new PluginLogStoreConfig.Retention();
    retention1.setDefaultPeriodMinutes(50);
    config.setRetention(retention1);
    assertThat(config.getEffectiveRetention()).isEqualTo(Duration.ofMinutes(50));

    final PluginLogStoreConfig.Retention retention2 = new PluginLogStoreConfig.Retention();
    retention2.setDefaultPeriodMinutes(200);
    config.setRetention(retention2);
    assertThat(config.getEffectiveRetention()).isEqualTo(Duration.ofMinutes(200));
  }

  @Test
  void testEffectiveRetentionEdgeCaseExactlyAtMax() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(1440);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofDays(1));
    assertThat(effective.toMinutes()).isEqualTo(config.getMaxRetentionMinutes());
  }

  @Test
  void testEffectiveRetentionEdgeCaseJustAboveMax() {
    final PluginLogStoreConfig.Retention retention = new PluginLogStoreConfig.Retention();
    retention.setDefaultPeriodMinutes(1441);
    config.setRetention(retention);

    final Duration effective = config.getEffectiveRetention();
    assertThat(effective).isEqualTo(Duration.ofDays(1));
  }
}
