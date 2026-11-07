// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/** Layers inside core: the data types sit under the services, and nothing runs in a cycle. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = {
      com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class,
      Modules.OnlyCore.class
    })
class CoreRules {

  @ArchTest
  /* default */ static final ArchRule DATA_TYPES_DO_NOT_USE_SERVICES =
      noClasses()
          .that()
          .resideInAnyPackage(
              "com.infenia.yukta.model..",
              "com.infenia.yukta.validation..",
              "com.infenia.yukta.util..",
              "com.infenia.yukta.event..",
              "com.infenia.yukta.edition..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.infenia.yukta.service..",
              "com.infenia.yukta.config..",
              "com.infenia.yukta.startup..");

  // Frozen: the cycles that exist today are listed in src/test/resources/frozen; new ones fail.
  @ArchTest
  /* default */ static final ArchRule TOP_LEVEL_SLICES_ARE_ACYCLIC =
      FreezingArchRule.freeze(
          slices().matching("com.infenia.yukta.(*)..").should().beFreeOfCycles());
}
