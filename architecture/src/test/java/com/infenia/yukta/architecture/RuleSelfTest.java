// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.architecture.fixture.BadBlocking;
import com.infenia.yukta.architecture.fixture.BadControlCaller;
import com.infenia.yukta.architecture.fixture.BadDeprecated;
import com.infenia.yukta.architecture.fixture.BadFieldInjection;
import com.infenia.yukta.architecture.fixture.BadLogger;
import com.infenia.yukta.architecture.fixture.BadSleep;
import com.infenia.yukta.architecture.fixture.BadSql;
import com.infenia.yukta.architecture.fixture.BadThrows;
import com.infenia.yukta.architecture.fixture.BadUtils;
import com.infenia.yukta.architecture.fixture.GoodClass;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A rule that silently matches nothing protects nothing. Each rule here must fail on a small class
 * that breaks it and accept a clean one.
 */
class RuleSelfTest {

  private static final String SELF_TEST = "self-test";

  private static void assertViolated(final ArchRule rule, final Class<?>... bad) {
    assertThatThrownBy(() -> rule.check(new ClassFileImporter().importClasses(bad)))
        .isInstanceOf(AssertionError.class);
  }

  /** Rules that select by a feature the class lacks match nothing; that is fine here. */
  private static void assertAccepted(final ArchRule rule, final Class<?>... good) {
    assertThatCode(
            () -> rule.allowEmptyShould(true).check(new ClassFileImporter().importClasses(good)))
        .doesNotThrowAnyException();
  }

  @Test
  void genericExceptionsAreCaught() {
    assertViolated(HygieneRules.NO_GENERIC_EXCEPTIONS, BadThrows.class);
  }

  @Test
  void deprecatedApiIsCaughtUnlessExempt() {
    final var classes = new ClassFileImporter().importClasses(BadDeprecated.class);
    assertThatThrownBy(() -> DeprecationRules.check(classes, Map.of()))
        .isInstanceOf(AssertionError.class);
    assertThatCode(
            () -> DeprecationRules.check(classes, Map.of(BadDeprecated.class.getName(), SELF_TEST)))
        .doesNotThrowAnyException();
  }

  @Test
  void fieldInjectionIsCaught() {
    assertViolated(HygieneRules.NO_FIELD_INJECTION, BadFieldInjection.class);
  }

  @Test
  void handWrittenLoggersAreCaught() {
    assertViolated(HygieneRules.LOGGERS_HAVE_THE_LOMBOK_SHAPE, BadLogger.class);
  }

  @Test
  void instantiableUtilityClassesAreCaught() {
    assertViolated(HygieneRules.UTILITIES_NOT_INSTANTIABLE, BadUtils.class);
  }

  @Test
  void forbiddenPackagesAreCaught() {
    assertViolated(
        HygieneRules.noDependencyOn(DescribedPredicate.alwaysTrue(), SELF_TEST, "java.sql.."),
        BadSql.class);
  }

  @Test
  void blockingCallsAreCaughtUnlessExempt() {
    final ArchRule strict = ReactiveRules.noBlockingCalls(Map.of());
    assertViolated(strict, BadBlocking.class);
    assertViolated(strict, BadSleep.class);
    final ArchRule exempt =
        ReactiveRules.noBlockingCalls(
            Map.of(BadBlocking.class.getName(), SELF_TEST, BadSleep.class.getName(), SELF_TEST));
    assertAccepted(exempt, BadBlocking.class, BadSleep.class);
  }

  @Test
  void aCommandSentAroundTheOneEntryIsCaught() {
    assertViolated(ControlEntryRules.ONLY_CONTROL_APPLIES_COMMANDS, BadControlCaller.class);
    assertAccepted(ControlEntryRules.ONLY_CONTROL_APPLIES_COMMANDS, GoodClass.class);
  }

  @Test
  void cleanClassPassesEveryRule() {
    final List<ArchRule> rules =
        List.of(
            HygieneRules.NO_GENERIC_EXCEPTIONS,
            HygieneRules.NO_JAVA_UTIL_LOGGING,
            HygieneRules.NO_STANDARD_STREAMS,
            HygieneRules.NO_FIELD_INJECTION,
            HygieneRules.JACKSON_3_ONLY,
            HygieneRules.LOGGERS_HAVE_THE_LOMBOK_SHAPE,
            HygieneRules.UTILITIES_NOT_INSTANTIABLE,
            ReactiveRules.noBlockingCalls(Map.of()));
    for (final ArchRule rule : rules) {
      assertAccepted(rule, GoodClass.class);
    }
  }
}
