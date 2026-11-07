// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The interface modules stay free of the web, data and security stacks. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = ImportOption.DoNotIncludeTests.class)
class PurityRules {

  private static final String[] STACKS = {
    "org.springframework.web..",
    "org.springframework.http..",
    "org.springframework.boot.web..",
    "org.springframework.jdbc..",
    "org.springframework.r2dbc..",
    "org.springframework.data..",
    "org.springframework.security..",
    "jakarta.servlet.."
  };

  @ArchTest
  /* default */ static final ArchRule PLUGIN_API_IS_PURE =
      noClasses()
          .that(Modules.inModule("/plugin-api/"))
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(STACKS);

  @ArchTest
  /* default */ static final ArchRule MESSAGING_IS_PURE =
      noClasses()
          .that(Modules.inModule("/messaging/"))
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(STACKS);
}
