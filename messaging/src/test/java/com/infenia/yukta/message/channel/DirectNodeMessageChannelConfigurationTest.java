// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

  /** Stub provider for tests. */
  private static final class StubNodeMessageChannelProvider implements NodeMessageChannelProvider {
    @Override
    public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
      return null;
    }
  }
}
