// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Keeps the gated modules findable by name in the native image. {@link
 * FeatureGateEnvironmentPostProcessor} checks {@code Class.forName} of a provider class; a native
 * image answers only for registered classes, so without this a Plus binary refused {@code
 * yukta.session.store-type=postgres} as if the PostgreSQL module were missing. A provider that is
 * not in the build is skipped.
 */
@Configuration(proxyBeanMethods = false)
@NoArgsConstructor
@ImportRuntimeHints(FeatureGateHints.Hints.class)
public class FeatureGateHints {

  /** Registers each provider class that is on the build's class path. */
  @NoArgsConstructor
  /* default */ static class Hints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader classLoader) {
      register(hints, classLoader, FeatureGateEnvironmentPostProcessor.providers());
    }

    /** Registers each of {@code names} that the class loader finds. */
    /* default */ static void register(
        final RuntimeHints hints,
        final @Nullable ClassLoader classLoader,
        final java.util.Collection<String> names) {
      for (final String name : names) {
        if (org.springframework.util.ClassUtils.isPresent(name, classLoader)) {
          hints.reflection().registerType(TypeReference.of(name));
        }
      }
    }
  }
}
