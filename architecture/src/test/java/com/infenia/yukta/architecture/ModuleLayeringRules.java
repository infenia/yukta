// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module layering: messaging and plugin-api define the interfaces, core builds on them, plugins
 * implement plugin-api only, and web, mcp and plus use core. Only boot may depend on the top layers
 * (not checked: Gradle already keeps anything else off boot).
 *
 * <p>Modules share packages (plugin-api and the plugins are both under {@code ..plugin..}), so a
 * layer is the Gradle module a class was loaded from, not a package.
 */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleLayeringRules {

  private static final String WEB = "Web";
  private static final String MCP = "Mcp";
  private static final String PLUS = "Plus";

  @ArchTest
  /* default */ static final ArchRule MODULES_FOLLOW_THE_LAYERING =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Messaging")
          .definedBy(Modules.inModule("/messaging/"))
          .layer("PluginApi")
          .definedBy(Modules.inModule("/plugin-api/"))
          .layer("Core")
          .definedBy(Modules.inModule("/core/"))
          .layer("Plugins")
          .definedBy(Modules.inModule("/plugins/"))
          .layer(WEB)
          .definedBy(Modules.inModule("/web/"))
          .layer(MCP)
          .definedBy(Modules.inModule("/mcp/"))
          .layer(PLUS)
          .definedBy(Modules.inModule("/plus/"))
          .whereLayer("Plugins")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer(WEB)
          .mayNotBeAccessedByAnyLayer()
          .whereLayer(MCP)
          .mayNotBeAccessedByAnyLayer()
          .whereLayer(PLUS)
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Core")
          .mayOnlyBeAccessedByLayers(WEB, MCP, PLUS)
          .whereLayer("PluginApi")
          .mayOnlyBeAccessedByLayers("Core", "Plugins", WEB, MCP, PLUS);
}
