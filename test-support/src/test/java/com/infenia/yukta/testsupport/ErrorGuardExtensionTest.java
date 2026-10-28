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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** The extension itself: what it does in each mode, driven by a stubbed JUnit context. */
@ExpectedErrorLog({"ERROR guard\\.[a-z]+ - ", "dropped error: .*IllegalStateException"})
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.UseConcurrentHashMap",
  "PMD.TooManyMethods"
})
@NoArgsConstructor
class ErrorGuardExtensionTest {

  private final ErrorGuardExtension extension = new ErrorGuardExtension();

  // Reset at the start, not the end: the guard that watches this very test runs after any
  // @AfterEach, and must see the mode the test left behind.
  @BeforeEach
  void resetProperties() {
    System.clearProperty(ErrorGuardExtension.MODE_PROPERTY);
    System.clearProperty(ErrorGuardExtension.STALE_PROPERTY);
    System.setProperty(ErrorGuardExtension.MODULE_WIDE_PROPERTY, "false");
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
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "report");
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
  void strict_failsWhenMethodExpectationMatchesNothing() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "expecting");
    extension.beforeEach(context);
    assertThatThrownBy(() -> extension.afterEach(context))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("unused @ExpectedErrorLog(\"went wrong\")")
        .hasMessageContaining("remove it");
  }

  @Test
  void staleCheckOff_letsAnUnusedMethodExpectationPass() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    System.setProperty(ErrorGuardExtension.STALE_PROPERTY, "false");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "expecting");
    extension.beforeEach(context);
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void afterAll_failsForClassExpectationNoTestUsed() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Unused.class, "plain");
    extension.beforeEach(context);
    extension.afterEach(context);
    assertThatThrownBy(() -> extension.afterAll(context))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("unused @ExpectedErrorLog(\"never happens\") on class Unused");
  }

  @Test
  void afterAll_passesWhenOneTestUsedTheClassExpectation() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Outer.Inner.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("went wrong");
    extension.afterEach(context);
    final ExtensionContext outer = context(GuardFixtures.Outer.class, "plain");
    when(outer.getRoot()).thenReturn(context);
    assertThatCode(() -> extension.afterAll(outer)).doesNotThrowAnyException();
  }

  @Test
  void afterAll_isSilentWhenTheGuardIsOffOrNothingIsExpected() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext plain = context(GuardFixtures.Plain.class, "plain");
    assertThatCode(() -> extension.afterAll(plain)).doesNotThrowAnyException();
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "off");
    final ExtensionContext unused = context(GuardFixtures.Unused.class, "plain");
    assertThatCode(() -> extension.afterAll(unused)).doesNotThrowAnyException();
  }

  @Test
  void awaitLogged_waitsForAnErrorLoggedOnAnotherThread() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "expecting");
    extension.beforeEach(context);
    final Thread later =
        new Thread(
            () -> {
              LockSupport.parkNanos(Duration.ofMillis(150).toNanos());
              LoggerFactory.getLogger("guard.later").error("went wrong later");
            });
    later.start();
    assertThatCode(() -> ErrorGuardExtension.awaitLogged("went wrong later", Duration.ofSeconds(5)))
        .doesNotThrowAnyException();
    later.join();
    extension.afterEach(context);
  }

  @Test
  void awaitLogged_returnsAtOnceWhenNoGuardIsRecording() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    // a finished test leaves no probe behind, as in a run where the extension is not registered
    extension.beforeEach(context);
    extension.afterEach(context);
    assertThatCode(() -> ErrorGuardExtension.awaitLogged("never logged", Duration.ofSeconds(30)))
        .doesNotThrowAnyException();
  }

  @Test
  void awaitLogged_failsWhenNothingMatchesInTime() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("something else");
    assertThatThrownBy(
            () -> ErrorGuardExtension.awaitLogged("never logged", Duration.ofMillis(100)))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("Nothing matching /never logged/")
        .hasMessageContaining("something else");
    assertThatThrownBy(() -> extension.afterEach(context)).isInstanceOf(AssertionFailedError.class);
  }

  @Test
  void moduleWide_acceptsAnErrorSourceThatAnotherTestDeclares() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    System.setProperty(ErrorGuardExtension.MODULE_WIDE_PROPERTY, "true");
    // "went wrong" is declared by the fixtures: a late error from that test is not this test's
    // fault
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("guard.log").error("went wrong");
    assertThatCode(() -> extension.afterEach(context)).doesNotThrowAnyException();
  }

  @Test
  void moduleWide_stillFailsAnErrorSourceNoTestDeclares() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    System.setProperty(ErrorGuardExtension.MODULE_WIDE_PROPERTY, "true");
    final ExtensionContext context = context(GuardFixtures.Plain.class, "plain");
    extension.beforeEach(context);
    LoggerFactory.getLogger("other.source").error("a source no test declares");
    assertThatThrownBy(() -> extension.afterEach(context))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("a source no test declares");
    // the guard that watches this test must not fail it for the error it provoked on purpose
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "off");
  }

  @Test
  void scan_findsTheDeclarationsOfTheModule_andFallsBackToNothing() {
    assertThat(ErrorGuardExtension.scan(GuardFixtures.class))
        .extracting(java.util.regex.Pattern::pattern)
        .contains("went wrong", "never happens");
    // a JDK class has no code source to scan: the guard then uses the running test only
    assertThat(ErrorGuardExtension.scan(String.class)).isEmpty();
  }

  @Test
  void sometimes_isAllowedButNeverReportedUnused() throws Exception {
    System.setProperty(ErrorGuardExtension.MODE_PROPERTY, "strict");
    final ExtensionContext methodLevel = context(GuardFixtures.Plain.class, "sometimes");
    extension.beforeEach(methodLevel);
    // the error did not happen in this run: a required pattern would be reported as unused
    assertThatCode(() -> extension.afterEach(methodLevel)).doesNotThrowAnyException();
    final ExtensionContext happened = context(GuardFixtures.Plain.class, "sometimes");
    extension.beforeEach(happened);
    LoggerFactory.getLogger("guard.log").error("timing dependent");
    assertThatCode(() -> extension.afterEach(happened)).doesNotThrowAnyException();
    final ExtensionContext classLevel = context(GuardFixtures.Sometimes.class, "plain");
    assertThatCode(() -> extension.afterAll(classLevel)).doesNotThrowAnyException();
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
    when(store.getOrComputeIfAbsent(any(), any(), any(Class.class)))
        .thenAnswer(
            call ->
                data.computeIfAbsent(
                    call.getArgument(0), k -> java.util.concurrent.ConcurrentHashMap.newKeySet()));
    doAnswer(call -> data.put(call.getArgument(0), call.getArgument(1)))
        .when(store)
        .put(any(), any());
    when(store.remove(any(), any(Class.class)))
        .thenAnswer(call -> data.remove(call.getArgument(0)));
    final ExtensionContext context = mock(ExtensionContext.class);
    final Method target = type.getDeclaredMethod(method);
    when(context.getStore(any())).thenReturn(store);
    when(context.getRoot()).thenReturn(context);
    when(context.getElement()).thenReturn(Optional.of(target));
    when(context.getRequiredTestClass()).thenReturn((Class) type);
    when(context.getDisplayName()).thenReturn(method + "()");
    return context;
  }
}
