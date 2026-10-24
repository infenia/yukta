// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** The extension itself: what it does in each mode, driven by a stubbed JUnit context. */
@ExpectedErrorLog(".*")
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.UseConcurrentHashMap"
})
@NoArgsConstructor
class ErrorGuardExtensionTest {

  private final ErrorGuardExtension extension = new ErrorGuardExtension();

  @AfterEach
  void clearMode() {
    System.clearProperty(ErrorGuardExtension.MODE_PROPERTY);
  }

  @Test
  void strict_failsTestThatDropsError() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    Mono.error(new IllegalStateException("boom")).subscribe();
    assertThatThrownBy(() -> extension.afterEach(context))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("boom")
        .hasMessageContaining("Plain");
  }

  @Test
  void strict_failsTestThatLogsError() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("went wrong");
    assertThatThrownBy(() -> extension.afterEach(context))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("went wrong");
  }

  @Test
  void strict_passesCleanTest() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").warn("only a warning");
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void strict_passesWhenTheMethodExpectsTheError() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "expecting");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("went wrong");
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void strict_passesWhenTheEnclosingClassExpectsTheError() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Outer.Inner.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("went wrong");
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void report_passesButStillReads() throws Exception {
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    Mono.error(new IllegalStateException("boom")).subscribe();
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void off_recordsNothing() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "off");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("went wrong");
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void present_isFalseForMissingClass() {
    assertThat(ErrorGuardExtension.present("no.such.Type")).isFalse();
    assertThat(ErrorGuardExtension.present("java.lang.String")).isTrue();
  }

  private static ExtensionContext context(final Class<?> type, final String method)
      throws NoSuchMethodException {
    final Map<Object, Object> data = new HashMap<>();
    final ExtensionContext.Store store = mock(ExtensionContext.Store.class);
    doAnswer(call -> data.put(call.getArgument(0), call.getArgument(1)))
        .when(store)
        .put(any(), any());
    when(store.remove(any(), any(Class.class)))
        .thenAnswer(call -> data.remove(call.getArgument(0)));
    final ExtensionContext context = mock(ExtensionContext.class);
    final Method target = type.getDeclaredMethod(method);
    when(context.getStore(any())).thenReturn(store);
    when(context.getElement()).thenReturn(Optional.of(target));
    when(context.getRequiredTestClass()).thenReturn((Class) type);
    when(context.getDisplayName()).thenReturn(method + "()");
    return context;
  }
}
