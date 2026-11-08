// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Structure rules that every module's runner applies to its own classes. */
public final class StructureRules {

  /** Top-level packages of one module do not depend on each other in a circle. */
  @ArchTest
  public static final ArchRule NO_PACKAGE_CYCLES =
      slices()
          .matching("com.infenia.yukta.(*)..")
          .should()
          .beFreeOfCycles()
          .as("top-level packages of a module are free of cycles")
          .because("a cycle means two packages cannot be understood, tested or moved apart");

  private StructureRules() {}
}
