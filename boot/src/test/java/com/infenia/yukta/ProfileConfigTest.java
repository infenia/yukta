// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Guards the profile-specific config: nothing sensitive may leak into the default or prod. */
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.CommentRequired"})
@NoArgsConstructor
class ProfileConfigTest {

  private static final String STREAM =
      "logging.level.com.infenia.yukta.service.orchestrator.stream";
  private static final String EXPOSURE = "management.endpoints.web.exposure.include";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void prodHasNoPayloadLoggingOrDebugActuator() {
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(
            ctx -> {
              assertThat(ctx.getEnvironment().getProperty(STREAM)).isNull();
              assertThat(ctx.getEnvironment().getProperty("logging.level.com.infenia.yukta"))
                  .isEqualTo("INFO");
              assertThat(ctx.getEnvironment().getProperty(EXPOSURE))
                  .isEqualTo("health,info,metrics");
              assertThat(
                      ctx.getEnvironment()
                          .getProperty("spring.main.lazy-initialization", Boolean.class, false))
                  .isFalse();
            });
  }

  @Test
  void defaultProfileIsProdWithNoPayloadLoggingOrDebugActuator() {
    runner.run(
        ctx -> {
          assertThat(ctx.getEnvironment().getProperty(STREAM)).isNull();
          assertThat(ctx.getEnvironment().getProperty(EXPOSURE)).isEqualTo("health,info,metrics");
        });
  }

  @Test
  void devHasNoPayloadTraceAndNoDebugActuators() {
    runner
        .withPropertyValues("spring.profiles.active=dev")
        .run(
            ctx -> {
              assertThat(ctx.getEnvironment().getProperty(STREAM)).isNull();
              assertThat(ctx.getEnvironment().getProperty(EXPOSURE))
                  .isEqualTo("health,info,metrics");
            });
  }
}
