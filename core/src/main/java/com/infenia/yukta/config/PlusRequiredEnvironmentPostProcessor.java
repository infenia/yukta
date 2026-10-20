// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import java.util.List;
import lombok.NoArgsConstructor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Stops start-up with a clear message when a setting for on-disk storage is used in a build that
 * does not have it. Without this the Community build would carry on in memory, or fail later with a
 * missing bean, and the data the user expected on disk would be lost on restart.
 */
@NoArgsConstructor
public class PlusRequiredEnvironmentPostProcessor implements EnvironmentPostProcessor {

  /** A class that exists only in the Plus build. */
  private static final String PLUS_MARKER =
      "com.infenia.yukta.service.session.store.FileSessionConfigStore";

  /** Directory settings that need Plus unless their store type says PostgreSQL. */
  private static final List<String> DIRECTORIES =
      List.of(
          "yukta.session.store-type=file",
          "yukta.execution-log.dir",
          "yukta.restart.dir",
          "yukta.session-memory.dir");

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    check(environment, ClassUtils.isPresent(PLUS_MARKER, null));
  }

  /* default */ static void check(final Environment environment, final boolean plusPresent) {
    if (!plusPresent) {
      DIRECTORIES.stream()
          .filter(setting -> isUsed(environment, setting))
          .findFirst()
          .ifPresent(
              setting -> {
                throw new IllegalStateException(
                    setting
                        + " needs the Yukta Plus build (AGPL-3.0-only). Build with"
                        + " -Pyukta.edition=plus, or remove the setting to keep state in memory."
                        + " See LICENSING.md.");
              });
    }
  }

  private static boolean isUsed(final Environment environment, final String setting) {
    final int equals = setting.indexOf('=');
    final boolean used;
    if (equals > 0) {
      used =
          setting
              .substring(equals + 1)
              .equals(environment.getProperty(setting.substring(0, equals)));
    } else {
      final String store = environment.getProperty(setting.replaceAll("\\.dir$", ".store-type"));
      used = StringUtils.hasText(environment.getProperty(setting)) && !"postgres".equals(store);
    }
    return used;
  }
}
