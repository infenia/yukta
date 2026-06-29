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

import com.infenia.yukta.message.store.MessageStore;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;

/** Tests for {@link PersistingNodeMessageChannelConfiguration}. */
@NoArgsConstructor
class PersistingNodeMessageChannelConfigurationTest {

  /** Context runner for testing. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(PersistingNodeMessageChannelConfiguration.class);

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void persistingNodeMessageChannelProvider_whenMessageStorePresent_registersPersistingProvider() {
    final MessageStore messageStore = new StubMessageStore();

    contextRunner
        .withBean(MessageStore.class, () -> messageStore)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
              assertThat(context.getBean(NodeMessageChannelProvider.class))
                  .isInstanceOf(PersistingNodeMessageChannelProvider.class);
            });
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void persistingNodeMessageChannelProvider_whenMessageStoreAbsent_doesNotRegisterBean() {
    contextRunner.run(
        context ->
            assertThat(context)
                .doesNotHaveBean(NodeMessageChannelProvider.class)
                .doesNotHaveBean(MessageStore.class));
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void persistingProvider_whenMultipleProvidersPresent_registersAllBeans() {
    final MessageStore messageStore = new StubMessageStore();
    final NodeMessageChannelProvider customProvider = new StubNodeMessageChannelProvider();

    contextRunner
        .withBean(MessageStore.class, () -> messageStore)
        .withBean("customProvider", NodeMessageChannelProvider.class, () -> customProvider)
        .run(
            context -> {
              assertThat(context).hasBean("persistingNodeMessageChannelProvider");
              assertThat(context).hasBean("customProvider");
              assertThat(context.getBean("customProvider", NodeMessageChannelProvider.class))
                  .isSameAs(customProvider);
              assertThat(
                      context.getBean(
                          "persistingNodeMessageChannelProvider", NodeMessageChannelProvider.class))
                  .isInstanceOf(PersistingNodeMessageChannelProvider.class);
            });
  }

  /** Stub message store for tests. */
  private static final class StubMessageStore implements MessageStore {

    @Override
    public Mono<Void> store(final com.infenia.yukta.message.Message<?> message) {
      return Mono.empty();
    }

    @Override
    public Mono<com.infenia.yukta.message.Message<?>> get(final String messageId) {
      return Mono.empty();
    }
  }

  /** Stub provider for tests. */
  private static final class StubNodeMessageChannelProvider implements NodeMessageChannelProvider {

    @Override
    public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
      return null;
    }
  }
}
