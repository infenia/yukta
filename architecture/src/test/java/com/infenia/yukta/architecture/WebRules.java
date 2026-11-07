// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Inside web: DTOs stay plain, controllers are REST controllers. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = {ImportOption.DoNotIncludeTests.class, Modules.OnlyWeb.class})
class WebRules {

  @ArchTest
  /* default */ static final ArchRule DTOS_DO_NOT_USE_CONTROLLERS =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..controller..");

  @ArchTest
  /* default */ static final ArchRule CONTROLLERS_ARE_REST =
      classes()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .and()
          .areTopLevelClasses()
          .should()
          .beAnnotatedWith("org.springframework.web.bind.annotation.RestController");
}
