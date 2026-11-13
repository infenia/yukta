// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.security.ApiKeys;
import lombok.NoArgsConstructor;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
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
      registerFields(hints, YuktaProperties.class);
      // ApiKeys binds yukta.api-keys with a raw Binder, which AOT does not see: without this the
      // native binary reads no keys and the exposure guard refuses to start in a container.
      new BindingReflectionHintsRegistrar()
          .registerReflectionHints(hints.reflection(), ApiKeys.KeyEntry.class);
    }

    /**
     * Bean validation reads the record fields of the {@code yukta.*} properties (and their nested
     * records) reflectively; AOT only registers the constructors.
     */
    private static void registerFields(final RuntimeHints hints, final Class<?> type) {
      hints.reflection().registerType(type, MemberCategory.ACCESS_DECLARED_FIELDS);
      for (final Class<?> nested : type.getDeclaredClasses()) {
        registerFields(hints, nested);
      }
    }
  }
}
