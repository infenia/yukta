// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
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
}
