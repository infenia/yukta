// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.security.ApiKeys;
import java.util.List;
import lombok.NoArgsConstructor;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
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

    /**
     * The JDK types whose methods a workflow expression may call ({@code SafeMethodResolver} lists
     * the method names, and runs them by reflection): {@code items.toString()}, {@code
     * name.substring(1)}, {@code payload.keySet()}. Without a registration the native image fails
     * the call with "Cannot reflectively invoke method". The immutable and unmodifiable collections
     * are package-private, so they are named. Nothing outside the JDK is registered.
     */
    /* default */ static final List<String> RECORD_PACKAGES =
        List.of("com.infenia.yukta", "io.modelcontextprotocol.spec");

    /* default */ static final List<String> EXPRESSION_TYPES =
        List.of(
            "java.lang.Object",
            "java.lang.Iterable",
            "java.util.Collection",
            "java.util.List",
            "java.util.Set",
            "java.util.Map",
            "java.lang.String",
            "java.lang.Number",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Boolean",
            "java.lang.Character",
            "java.lang.Enum",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.Optional",
            "java.util.AbstractCollection",
            "java.util.AbstractList",
            "java.util.AbstractSet",
            "java.util.AbstractMap",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.HashMap$KeySet",
            "java.util.HashMap$Values",
            "java.util.LinkedHashMap$LinkedKeySet",
            "java.util.LinkedHashMap$LinkedValues",
            "java.util.ImmutableCollections$AbstractImmutableCollection",
            "java.util.ImmutableCollections$AbstractImmutableList",
            "java.util.ImmutableCollections$List12",
            "java.util.ImmutableCollections$ListN",
            "java.util.ImmutableCollections$AbstractImmutableSet",
            "java.util.ImmutableCollections$Set12",
            "java.util.ImmutableCollections$SetN",
            "java.util.ImmutableCollections$AbstractImmutableMap",
            "java.util.ImmutableCollections$Map1",
            "java.util.ImmutableCollections$MapN",
            "java.util.Collections$UnmodifiableCollection",
            "java.util.Collections$UnmodifiableList",
            "java.util.Collections$UnmodifiableRandomAccessList",
            "java.util.Collections$UnmodifiableSet",
            "java.util.Collections$UnmodifiableMap",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.Duration",
            "java.time.Period");

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
      // Jackson builds the type of ApiResponse.errors through its array class; without the array
      // registered every REST response fails in the native image ("Cannot reflectively instantiate
      // the array class ...FieldError[]").
      new BindingReflectionHintsRegistrar()
          .registerReflectionHints(
              hints.reflection(), ApiResponse.class, ApiResponse.FieldError.class);
      registerRecordArrays(hints);
      EXPRESSION_TYPES.forEach(
          name ->
              hints
                  .reflection()
                  .registerType(TypeReference.of(name), MemberCategory.INVOKE_PUBLIC_METHODS));
    }

    /**
     * Jackson reads a {@code List<Record>} field through the record's array class, which a native
     * image only allows when it is registered: {@code TaskProgress[]}, {@code InputOptionView[]},
     * {@code McpSchema$Tool[]} each failed a REST or MCP call. Every record of Yukta and of the MCP
     * schema gets its array registered, so a new DTO does not need a hint of its own.
     */
    private static void registerRecordArrays(final RuntimeHints hints) {
      final ClassPathScanningCandidateComponentProvider scanner =
          new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(final AnnotatedBeanDefinition definition) {
              return true;
            }
          };
      scanner.addIncludeFilter(
          (reader, factory) ->
              "java.lang.Record".equals(reader.getClassMetadata().getSuperClassName()));
      for (final String basePackage : RECORD_PACKAGES) {
        for (final BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
          hints.reflection().registerType(TypeReference.of(definition.getBeanClassName() + "[]"));
        }
      }
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
