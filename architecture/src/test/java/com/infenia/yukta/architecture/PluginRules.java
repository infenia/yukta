// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import java.util.Set;

/** Plugins are independent modules that implement plugin-api. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = ImportOption.DoNotIncludeTests.class)
class PluginRules {

  private static final String PLUGINS_DIR = "/plugins/";

  /** The plugin-to-plugin dependencies that exist today ("from>to", by module directory). */
  private static final Set<String> ALLOWED =
      Set.of(
          "guards>rule-supervisor",
          "script-js>internal-core",
          "decision>llm-call",
          "model-control-plane>decision",
          "rule-control-plane>rule-supervisor",
          "rules-first-control-plane>rule-control-plane",
          "rules-first-control-plane>model-control-plane",
          "rules-first-control-plane>rule-supervisor",
          "rules-first-control-plane>decision");

  @ArchTest
  /* default */ static final ArchRule PLUGIN_DEPENDENCIES_AS_LISTED =
      classes()
          .that(Modules.inModule(PLUGINS_DIR))
          .should(
              new ArchCondition<>("depend on another plugin only as listed in ALLOWED") {
                @Override
                public void check(final JavaClass from, final ConditionEvents events) {
                  final String fromModule = Modules.nameOf(from).orElse("?");
                  for (final Dependency d : from.getDirectDependenciesFromSelf()) {
                    final JavaClass to = d.getTargetClass();
                    if (!Modules.inModule(PLUGINS_DIR).test(to)) {
                      continue;
                    }
                    final String toModule = Modules.nameOf(to).orElse("?");
                    if (!fromModule.equals(toModule)
                        && !ALLOWED.contains(fromModule + ">" + toModule)) {
                      events.add(violated(d, d.getDescription()));
                    }
                  }
                }
              });

  @ArchTest
  /* default */ static final ArchRule PLUGINS_IMPLEMENT_PLUGIN =
      classes()
          .that(Modules.inModule(PLUGINS_DIR))
          .and()
          .haveSimpleNameEndingWith("Plugin")
          .and()
          .areTopLevelClasses()
          .and()
          .areNotInterfaces()
          .and()
          .doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
          .should()
          .implement(com.infenia.yukta.plugin.core.Plugin.class);

  @ArchTest
  /* default */ static final ArchRule PLUGINS_AVOID_WEB_LAYER =
      noClasses()
          .that(Modules.inModule(PLUGINS_DIR))
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.infenia.yukta.controller..", "com.infenia.yukta.dto..");
}
