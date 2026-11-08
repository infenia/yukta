// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.infenia.yukta.architecture.Modules;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * How the Plus modules relate to the rest: they extend core through plugin-api, nothing in the
 * Apache modules knows them (LICENSING.md), and they stay off the REST layer. These rules look at
 * classes of several modules, so they run over all of them.
 */
public final class PlusRules {

  private static final String PLUS = "/plus/";

  @ArchTest
  public static final ArchRule PLUS_AVOIDS_THE_REST_LAYER =
      noClasses()
          .that(Modules.inModule(PLUS))
          .should()
          .dependOnClassesThat(Modules.inModule("/web/").or(Modules.inModule("/mcp/")))
          .because("Plus adds stores and workers behind core's interfaces, never HTTP surface");

  @ArchTest
  public static final ArchRule APACHE_MODULES_DO_NOT_USE_PLUS =
      noClasses()
          .that(
              Modules.inModule("/core/")
                  .or(Modules.inModule("/plugin-api/"))
                  .or(Modules.inModule("/messaging/"))
                  .or(Modules.inModule("/web/"))
                  .or(Modules.inModule("/mcp/")))
          .should()
          .dependOnClassesThat(Modules.inModule(PLUS))
          .because("Community never depends on Plus (LICENSING.md); only boot may wire it in");

  private PlusRules() {}
}
