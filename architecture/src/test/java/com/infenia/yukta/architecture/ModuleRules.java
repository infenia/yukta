// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import java.util.Map;
import java.util.Set;

/**
 * How the Apache modules relate: messaging and plugin-api define the interfaces, core builds on
 * them, plugins implement plugin-api only, and web and mcp use core. Only boot may depend on the
 * top layers (not checked: Gradle already keeps anything else off boot). The Plus modules may only
 * be used by boot (licensing), so they are checked by the {@code plus:plus-architecture} module.
 *
 * <p>Modules share packages (plugin-api and the plugins are both under {@code ..plugin..}), so a
 * layer is the Gradle module a class was loaded from, not a package.
 */
public final class ModuleRules {

  private static final String WEB = "Web";
  private static final String MCP = "Mcp";
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

  /** Classes that may block, with the reason; every entry parks a thread, so keep this short. */
  private static final Map<String, String> MAY_BLOCK =
      Map.of(
          "com.infenia.yukta.startup.BootstrapRunner",
          "applies the bootstrap once at start-up, before the server takes traffic",
          "com.infenia.yukta.plugin.mcp.McpClientGateway",
          "closes the MCP clients at shutdown");

  private static final String[] WEB_AND_DATA_STACKS = {
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
  public static final ArchRule MODULES_FOLLOW_THE_LAYERING =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Messaging")
          .definedBy(Modules.inModule("/messaging/"))
          .layer("PluginApi")
          .definedBy(Modules.inModule("/plugin-api/"))
          .layer("Core")
          .definedBy(Modules.inModule("/core/"))
          .layer("Plugins")
          .definedBy(Modules.inModule(PLUGINS_DIR))
          .layer(WEB)
          .definedBy(Modules.inModule("/web/"))
          .layer(MCP)
          .definedBy(Modules.inModule("/mcp/"))
          .whereLayer("Plugins")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer(WEB)
          .mayNotBeAccessedByAnyLayer()
          .whereLayer(MCP)
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Core")
          .mayOnlyBeAccessedByLayers(WEB, MCP)
          .whereLayer("PluginApi")
          .mayOnlyBeAccessedByLayers("Core", "Plugins", WEB, MCP)
          .because(
              "dependencies point one way: interfaces <- core <- web/mcp, plugins <- plugin-api");

  @ArchTest
  public static final ArchRule PLUGIN_DEPENDENCIES_AS_LISTED =
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
              })
          .because("plugins stay swappable; a new plugin-to-plugin edge is a design decision");

  @ArchTest
  public static final ArchRule PLUGINS_IMPLEMENT_PLUGIN =
      classes()
          .that(Modules.inModule(PLUGINS_DIR))
          .and()
          .haveSimpleNameEndingWith("Plugin")
          .and()
          .areTopLevelClasses()
          .and()
          .areNotInterfaces()
          .and()
          .doNotHaveModifier(JavaModifier.ABSTRACT)
          .should()
          .implement(com.infenia.yukta.plugin.core.Plugin.class)
          .because("the engine finds plugins by their Plugin interface");

  @ArchTest
  public static final ArchRule PLUGINS_AVOID_WEB_LAYER =
      noClasses()
          .that(Modules.inModule(PLUGINS_DIR))
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.infenia.yukta.controller..", "com.infenia.yukta.dto..")
          .because("plugins know nothing of the REST layer");

  @ArchTest
  public static final ArchRule PLUGIN_API_IS_PURE =
      HygieneRules.noDependencyOn(
          Modules.inModule("/plugin-api/"),
          "plugin-api is the contract plugin authors compile against; keep the web and data"
              + " stacks out of it",
          WEB_AND_DATA_STACKS);

  @ArchTest
  public static final ArchRule MESSAGING_IS_PURE =
      HygieneRules.noDependencyOn(
          Modules.inModule("/messaging/"),
          "messaging is shared by everything; keep the web and data stacks out of it",
          WEB_AND_DATA_STACKS);

  @ArchTest
  public static final ArchRule REST_ONLY_IN_WEB_AND_MCP =
      noClasses()
          .that(
              DescribedPredicate.not(
                  Modules.inModule("/web/").or(Modules.inModule("/mcp/")).as("are in web or mcp")))
          .should()
          .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .because("the REST surface is web's job; other modules stay free of HTTP concerns");

  @ArchTest
  public static final ArchRule NO_BLOCKING_CALLS = ReactiveRules.noBlockingCalls(MAY_BLOCK);

  /**
   * No class uses deprecated API.
   *
   * @param classes the imported classes
   */
  @ArchTest
  @SuppressWarnings("PMD.LooseCoupling")
  public static void noDeprecatedApi(final JavaClasses classes) {
    DeprecationRules.check(classes, Map.of());
  }

  private ModuleRules() {}
}
