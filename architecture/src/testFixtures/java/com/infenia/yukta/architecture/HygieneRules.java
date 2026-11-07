// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/** Coding rules for every production class of every module. */
public final class HygieneRules {

  /** Packages of Jackson 2; the project uses Jackson 3 ({@code tools.jackson}). */
  private static final String[] JACKSON_2 = {
    "com.fasterxml.jackson.databind..",
    "com.fasterxml.jackson.core..",
    "com.fasterxml.jackson.dataformat..",
    "com.fasterxml.jackson.datatype..",
    "com.fasterxml.jackson.module.."
  };

  @ArchTest
  public static final ArchRule NO_GENERIC_EXCEPTIONS =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.because(
          "callers and the retry policy tell failures apart by their exception type");

  @ArchTest
  public static final ArchRule NO_JAVA_UTIL_LOGGING =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because(
          "all logging goes through SLF4J (Lombok @Slf4j)");

  @ArchTest
  public static final ArchRule NO_STANDARD_STREAMS =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
          "output goes through the logger, never System.out/err or printStackTrace");

  @ArchTest
  public static final ArchRule NO_FIELD_INJECTION =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
          "constructor injection keeps dependencies visible and the class testable");

  @ArchTest
  public static final ArchRule JACKSON_3_ONLY =
      noDependencyOn(
          DescribedPredicate.alwaysTrue(),
          "Spring Boot 4 uses Jackson 3 (tools.jackson); Jackson 2 types never match what it"
              + " throws",
          JACKSON_2);

  @ArchTest
  public static final ArchRule LOGGERS_HAVE_THE_LOMBOK_SHAPE =
      fields()
          .that()
          .haveRawType("org.slf4j.Logger")
          .should()
          .bePrivate()
          .andShould()
          .beStatic()
          .andShould()
          .beFinal()
          .andShould()
          .haveName("log")
          .because("loggers come from Lombok @Slf4j, never hand-written")
          .allowEmptyShould(true);

  @ArchTest
  public static final ArchRule UTILITIES_NOT_INSTANTIABLE =
      classes()
          .that()
          .haveSimpleNameEndingWith("Utils")
          .or()
          .haveSimpleNameEndingWith("Util")
          .should()
          .haveModifier(JavaModifier.FINAL)
          .andShould()
          .haveOnlyPrivateConstructors()
          .because("static helpers use Lombok @UtilityClass or a private constructor")
          .allowEmptyShould(true);

  /**
   * No class of the origin may depend on the packages.
   *
   * @param origin the classes the rule is about
   * @param reason why the packages are off limits
   * @param packages package identifiers such as {@code org.springframework.web..}
   * @return the rule
   */
  public static ArchRule noDependencyOn(
      final DescribedPredicate<JavaClass> origin, final String reason, final String... packages) {
    return noClasses()
        .that(origin)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(packages)
        .because(reason);
  }

  private HygieneRules() {}
}
