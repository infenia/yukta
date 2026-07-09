// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Builds plugin guide templates. Encapsulates template code generation logic with no external
 * dependencies.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
@UtilityClass
public class PluginTemplateBuilder {

  /** Constant for selecting all plugin types. */
  private static final String ALL_TYPE = "all";

  /**
   * Build plugin code templates filtered by type.
   *
   * @param templateType filter type: "trigger", "processor", "terminal", or "all"
   * @return map of template code keyed by type
   */
  public static Map<String, String> buildTemplates(final String templateType) {
    final Map<String, String> templates = new LinkedHashMap<>();
    final String type = templateType == null || templateType.isBlank() ? ALL_TYPE : templateType;

    if (ALL_TYPE.equalsIgnoreCase(type) || "trigger".equalsIgnoreCase(type)) {
      templates.put(
          "trigger",
          """
          @Component
          public class MyTriggerPlugin extends TriggerPlugin {
            @Override
            public String getType() { return "my-trigger"; }
            @Override
            public Mono<String> execute(final WorkflowContext context) {
              // Trigger logic here
              return Mono.just("triggered");
            }
          }
          """);
    }

    if (ALL_TYPE.equalsIgnoreCase(type) || "processor".equalsIgnoreCase(type)) {
      templates.put(
          "processor",
          """
          @Component
          public class MyProcessorPlugin extends ProcessorPlugin {
            @Override
            public String getType() { return "my-processor"; }
            @Override
            public Mono<String> execute(final WorkflowContext context) {
              // Process data here
              return Mono.just("processed");
            }
          }
          """);
    }

    if (ALL_TYPE.equalsIgnoreCase(type) || "terminal".equalsIgnoreCase(type)) {
      templates.put(
          "terminal",
          """
          @Component
          public class MyTerminalPlugin extends TerminalPlugin {
            @Override
            public String getType() { return "my-terminal"; }
            @Override
            public Mono<Void> execute(final WorkflowContext context) {
              // Finalize workflow here
              return Mono.empty();
            }
          }
          """);
    }

    return templates;
  }
}
