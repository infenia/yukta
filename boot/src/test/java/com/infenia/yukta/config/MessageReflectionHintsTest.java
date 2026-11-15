// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/** The message record stays readable by reflection in a native image. */
@NoArgsConstructor
class MessageReflectionHintsTest {

  @Test
  void registerHints_messageRecord_isReadableByReflection() {
    final RuntimeHints hints = new RuntimeHints();

    new MessageReflectionHints.Hints()
        .registerHints(hints, Thread.currentThread().getContextClassLoader());

    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(DefaultMessage.class)
                .withMemberCategories(
                    MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS))
        .accepts(hints);
  }

  @Test
  void registerHints_apiKeyEntry_isBindableInNative() {
    final RuntimeHints hints = new RuntimeHints();

    new MessageReflectionHints.Hints()
        .registerHints(hints, Thread.currentThread().getContextClassLoader());

    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(com.infenia.yukta.security.ApiKeys.KeyEntry.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
        .accepts(hints);
  }

  @Test
  void registerHints_jdkTypesOfExpressionMethods_areInvocableInNative() {
    final RuntimeHints hints = new RuntimeHints();

    new MessageReflectionHints.Hints()
        .registerHints(hints, Thread.currentThread().getContextClassLoader());

    // items.toString() on a projection runs AbstractCollection.toString by reflection
    for (final Class<?> type :
        List.of(
            java.util.AbstractCollection.class,
            java.util.ArrayList.class,
            java.util.LinkedHashMap.class,
            String.class,
            java.time.Instant.class)) {
      assertThat(
              RuntimeHintsPredicates.reflection()
                  .onType(type)
                  .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
          .as(type.getName())
          .accepts(hints);
    }
  }

  @Test
  void expressionTypes_namedClasses_exist() {
    // a misspelt or removed JDK class would be registered for nothing
    for (final String name : MessageReflectionHints.Hints.EXPRESSION_TYPES) {
      org.assertj.core.api.Assertions.assertThatCode(
              () -> Class.forName(name, false, ClassLoader.getSystemClassLoader()))
          .as(name)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void registerHints_apiResponseAndItsErrorArray_areInstantiableInNative() {
    final RuntimeHints hints = new RuntimeHints();

    new MessageReflectionHints.Hints()
        .registerHints(hints, Thread.currentThread().getContextClassLoader());

    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(com.infenia.yukta.model.api.ApiResponse.FieldError.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
        .accepts(hints);
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(
                    org.springframework.aot.hint.TypeReference.of(
                        "com.infenia.yukta.model.api.ApiResponse$FieldError[]")))
        .accepts(hints);
    // any record of the project, and of the MCP schema, gets its array registered
    for (final String array :
        List.of(
            "com.infenia.yukta.model.execution.TaskProgress[]",
            "com.infenia.yukta.dto.response.InputRequestView$InputOptionView[]",
            "io.modelcontextprotocol.spec.McpSchema$Tool[]")) {
      assertThat(
              RuntimeHintsPredicates.reflection()
                  .onType(org.springframework.aot.hint.TypeReference.of(array)))
          .as(array)
          .accepts(hints);
    }
  }
}
