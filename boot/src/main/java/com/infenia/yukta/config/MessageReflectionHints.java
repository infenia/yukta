// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.message.DefaultMessage;
import lombok.NoArgsConstructor;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Keeps the reflection data of the message record in the native image. Expressions in workflows
 * (such as {@code payload.name}) read the message through reflection; AOT cannot see that, so
 * without this a native build fails every expression with "Property or field 'payload' cannot be
 * found".
 */
@Configuration(proxyBeanMethods = false)
@NoArgsConstructor
@ImportRuntimeHints(MessageReflectionHints.Hints.class)
public class MessageReflectionHints {

  /** Registers the message record for reflection. */
  @NoArgsConstructor
  /* default */ static class Hints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
      hints
          .reflection()
          .registerType(
              DefaultMessage.class,
              MemberCategory.INVOKE_PUBLIC_METHODS,
              MemberCategory.INVOKE_DECLARED_METHODS,
              MemberCategory.ACCESS_DECLARED_FIELDS);
    }
  }
}
