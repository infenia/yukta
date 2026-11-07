// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Layers are found by the directory a class was built in. If the build layout changes (output
 * directories, jar names) a layer would silently become empty; this fails first.
 */
class ModuleLocationTest {

  /** One class per module and the module directory it must resolve to. */
  private static final Map<String, String> ANCHORS =
      Map.of(
          "com.infenia.yukta.message.Message", "messaging",
          "com.infenia.yukta.plugin.core.Plugin", "plugin-api",
          "com.infenia.yukta.service.plugin.PluginRegistry", "core",
          "com.infenia.yukta.exception.GlobalExceptionHandler", "web",
          "com.infenia.yukta.mcp.AppMcpTools", "mcp",
          "com.infenia.yukta.plugin.http.HttpRequestPlugin", "http-request",
          "com.infenia.yukta.supervisor.guards.FailureGuardFactory", "guards");

  @Test
  @SuppressWarnings("PMD.LooseCoupling")
  void everyAnchorResolvesToItsModule() {
    final JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.infenia.yukta");
    ANCHORS.forEach(
        (name, module) -> assertThat(Modules.nameOf(classes.get(name))).as(name).contains(module));
    assertThat(
            Modules.inModule("/plugins/")
                .test(classes.get("com.infenia.yukta.plugin.http.HttpRequestPlugin")))
        .isTrue();
  }
}
