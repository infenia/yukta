// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Inside web: DTOs stay plain, controllers are REST controllers. */
public final class WebRules {

  @ArchTest
  public static final ArchRule DTOS_DO_NOT_USE_CONTROLLERS =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..controller..")
          .because("a DTO is plain data; the controller depends on it, not the other way round");

  @ArchTest
  public static final ArchRule CONTROLLERS_ARE_REST =
      classes()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .and()
          .areTopLevelClasses()
          .should()
          .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .because("a class named Controller that is not a RestController is never routed");

  private WebRules() {}
}
