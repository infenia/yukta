// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.util.ClassUtils;

/** A gated module in the build stays findable by name in a native image. */
@NoArgsConstructor
class FeatureGateHintsTest {

  @Test
  void registerHints_registersEveryProviderInTheBuild_andSkipsTheOthers() {
    final ClassLoader loader = Thread.currentThread().getContextClassLoader();
    final RuntimeHints hints = new RuntimeHints();

    new FeatureGateHints.Hints().registerHints(hints, loader);

    assertThat(FeatureGateEnvironmentPostProcessor.providers()).isNotEmpty();
    for (final String provider : FeatureGateEnvironmentPostProcessor.providers()) {
      assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(provider)).test(hints))
          .as(provider)
          .isEqualTo(ClassUtils.isPresent(provider, loader));
    }
  }

  @Test
  void registerHints_classNotInTheBuild_isNotRegistered() {
    final RuntimeHints hints = new RuntimeHints();

    new FeatureGateHints.Hints().registerHints(hints, new ClassLoader(null) {});

    assertThat(hints.reflection().typeHints()).isEmpty();
  }

  @Test
  void register_registersTheClassesItFinds_only() {
    final RuntimeHints hints = new RuntimeHints();

    FeatureGateHints.Hints.register(
        hints,
        Thread.currentThread().getContextClassLoader(),
        java.util.List.of("java.lang.String", "com.example.NotInTheBuild"));

    assertThat(RuntimeHintsPredicates.reflection().onType(String.class)).accepts(hints);
    assertThat(hints.reflection().typeHints()).hasSize(1);
  }
}
