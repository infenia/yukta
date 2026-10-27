// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Keeps the reflection data of the MCP result records in the native image. Jackson (tool output)
 * and springdoc ({@code /v3/api-docs}) read their components by reflection, which AOT cannot see;
 * without this a native build fails every tool call and the API description.
 */
@Configuration(proxyBeanMethods = false)
@NoArgsConstructor
@ImportRuntimeHints(McpDtoReflectionHints.Hints.class)
public class McpDtoReflectionHints {

  /** Registers every type of the {@code dto} package for reflection. */
  @NoArgsConstructor
  /* default */ static class Hints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
      final var scanner =
          new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(final AnnotatedBeanDefinition definition) {
              return true;
            }
          };
      scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
      scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
      for (final BeanDefinition candidate :
          scanner.findCandidateComponents(McpDtoReflectionHints.class.getPackageName() + ".dto")) {
        hints
            .reflection()
            .registerType(
                ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader),
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
      }
    }
  }
}
