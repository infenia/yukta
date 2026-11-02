// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.input.AnswerAuthorizer;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The rules for who may answer an input request are a default that a bean of your own replaces. */
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class AnswerAuthorizerAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DefaultsAutoConfiguration.class))
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void withNothingElseTheDefaultRulesApply() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(AnswerAuthorizer.class);
          assertThat(context.getBean(AnswerAuthorizer.class))
              .isInstanceOf(DefaultAnswerAuthorizer.class);
        });
  }

  @Test
  void theRunsAndMetersOfTheApplicationAreUsedWhenThereAreSome() {
    runner
        .withBean(ExecutionControlStore.class, InMemoryExecutionControlStore::new)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context ->
                assertThat(context.getBean(AnswerAuthorizer.class))
                    .isInstanceOf(DefaultAnswerAuthorizer.class));
  }

  @Test
  void beanOfYourOwnWinsOverTheDefault() {
    final AnswerAuthorizer allowAll = _ -> Mono.empty();

    runner
        .withBean("mine", AnswerAuthorizer.class, () -> allowAll)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AnswerAuthorizer.class);
              assertThat(context.getBean(AnswerAuthorizer.class)).isSameAs(allowAll);
              assertThat(context).doesNotHaveBean(DefaultAnswerAuthorizer.class);
            });
  }
}
