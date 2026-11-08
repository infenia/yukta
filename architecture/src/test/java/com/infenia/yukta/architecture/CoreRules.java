// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.plantuml.rules.PlantUmlArchCondition.Configuration.consideringOnlyDependenciesInDiagram;
import static com.tngtech.archunit.library.plantuml.rules.PlantUmlArchCondition.adhereToPlantUmlDiagram;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Inside core: the packages follow the diagram in {@code core-packages.puml}. */
public final class CoreRules {

  @ArchTest
  public static final ArchRule PACKAGES_FOLLOW_THE_DIAGRAM =
      classes()
          .should(
              adhereToPlantUmlDiagram(
                  CoreRules.class.getResource("/core-packages.puml"),
                  consideringOnlyDependenciesInDiagram()))
          .because("core-packages.puml is the documented target; change it with the code");

  private CoreRules() {}
}
