// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for {@link DirectNodeMessageChannelConfiguration}. */
@NoArgsConstructor
class DirectNodeMessageChannelConfigurationTest {

  /** PMD rule that does not see assertions inside lambdas. */
  private static final String NO_ASSERT = "PMD.UnitTestShouldIncludeAssert";

  /** Context runner for testing. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(DirectNodeMessageChannelConfiguration.class);

  @Test
  @SuppressWarnings(NO_ASSERT)
  void nodeMessageChannelProvider_whenNoBeanPresent_registersDirectNodeMessageChannelProvider() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
          assertThat(context.getBean(NodeMessageChannelProvider.class))
              .isInstanceOf(DirectNodeMessageChannelProvider.class);
        });
  }

  @Test
  @SuppressWarnings({NO_ASSERT, "PMD.LawOfDemeter"})
  void maxPayloadBytes_negativeValueIsRefusedAtStartup() {
    contextRunner
        .withPropertyValues("yukta.messaging.max-payload-bytes=-1")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("must be 0 (off) or positive"));
  }

  @Test
  @SuppressWarnings(NO_ASSERT)
  void maxPayloadBytes_acceptsSizeWithUnit() {
    contextRunner
        .withPropertyValues("yukta.messaging.max-payload-bytes=1KB")
        .run(context -> assertThat(context).hasSingleBean(NodeMessageChannelProvider.class));
  }

  @Test
  @SuppressWarnings(NO_ASSERT)
  void maxPayloadBytes_property_limitsTheChannel() {
    contextRunner
        .withPropertyValues("yukta.messaging.max-payload-bytes=3")
        .run(
            context -> {
              assertThat(context).hasSingleBean(NodeMessageChannelProvider.class);
              final var channel =
                  context.getBean(NodeMessageChannelProvider.class).channelFor("n", Map.of());
              StepVerifier.create(
                      channel.inbound(
                          "n", "e", Flux.just(DefaultMessage.create(UUID.randomUUID(), "abcd"))))
                  .expectError(PayloadTooLargeException.class)
                  .verify();
            });
  }

  @Test
  @SuppressWarnings(NO_ASSERT)
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
  @SuppressWarnings(NO_ASSERT)
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
