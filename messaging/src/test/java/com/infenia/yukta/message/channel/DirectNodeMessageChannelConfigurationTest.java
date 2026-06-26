/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DirectNodeMessageChannelConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(DirectNodeMessageChannelConfiguration.class);

  @Test
  void
      directNodeMessageChannelProvider_whenNoBeanPresent_registersDirectNodeMessageChannelProvider() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
          assertThat(context.getBean(NodeMessageChannelProvider.class))
              .isInstanceOf(DirectNodeMessageChannelProvider.class);
        });
  }

  @Test
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

  private static class StubNodeMessageChannelProvider implements NodeMessageChannelProvider {
    @Override
    public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
      return null;
    }
  }
}
