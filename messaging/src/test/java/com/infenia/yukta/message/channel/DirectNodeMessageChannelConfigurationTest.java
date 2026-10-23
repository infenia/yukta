// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tests for {@link DirectNodeMessageChannelConfiguration}. */
@NoArgsConstructor
class DirectNodeMessageChannelConfigurationTest {

  /** Context runner for testing. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(DirectNodeMessageChannelConfiguration.class);

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void nodeMessageChannelProvider_whenNoBeanPresent_registersDirectNodeMessageChannelProvider() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
          assertThat(context.getBean(NodeMessageChannelProvider.class))
              .isInstanceOf(DirectNodeMessageChannelProvider.class);
        });
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void directNodeMessageChannelProvider_whenCustomBeanPresent_doesNotRegisterDefaultBean() {
    final NodeMessageChannelProvider customProvider = new StubNodeMessageChannelProvider();

    contextRunner
        .withBean(NodeMessageChannelProvider.class, () -> customProvider)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
              assertThat(context.getBean(NodeMessageChannelProvider.class))
                  .isSameAs(customProvider);
            });
  }

  /** A user configuration that Spring reads in any position still beats the default. */
  @Configuration
  /* default */ static class CustomProviderConfiguration {
    @Bean
    /* default */ NodeMessageChannelProvider custom() {
      return new StubNodeMessageChannelProvider();
    }
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void autoConfiguredDefault_yieldsToCustomProviderRegardlessOfOrder() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DirectNodeMessageChannelConfiguration.class))
        .withUserConfiguration(CustomProviderConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
              assertThat(context.getBean(NodeMessageChannelProvider.class))
                  .isInstanceOf(StubNodeMessageChannelProvider.class);
            });
  }

  @Test
  void configuration_isListedAsAutoConfiguration() {
    assertThat(
            ImportCandidates.load(AutoConfiguration.class, ClassLoader.getSystemClassLoader())
                .getCandidates())
        .contains(DirectNodeMessageChannelConfiguration.class.getName());
  }

  /** Stub provider for tests. */
  private static final class StubNodeMessageChannelProvider implements NodeMessageChannelProvider {
    @Override
    public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
      return null;
    }
  }
}
