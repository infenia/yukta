// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import java.util.Locale;
import lombok.NoArgsConstructor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Stops start-up with a message that names the replacement when a setting that was removed is still
 * set. Without it the old setting would be ignored and the feature it configured would silently
 * stop.
 *
 * <p>{@code yukta.input.responders.<name>} became {@code
 * yukta.notifications.channels.webhook.<name>} when {@code InputResponder} was replaced by {@code
 * NotificationChannel}.
 */
@NoArgsConstructor
public class RemovedSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor {

  /** The removed prefix, as a property and as an environment variable. */
  private static final String OLD_PROPERTY = "yukta.input.responders.";

  private static final String OLD_VARIABLE = "YUKTA_INPUT_RESPONDERS_";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    for (final PropertySource<?> source : environment.getPropertySources()) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        for (final String name : enumerable.getPropertyNames()) {
          final String lower = name.toLowerCase(Locale.ROOT);
          if (lower.startsWith(OLD_PROPERTY) || name.startsWith(OLD_VARIABLE)) {
            throw new IllegalStateException(
                name
                    + " is no longer read: input responders are now notification channels. Rename"
                    + " yukta.input.responders.<name> to"
                    + " yukta.notifications.channels.webhook.<name>.");
          }
        }
      }
    }
  }
}
