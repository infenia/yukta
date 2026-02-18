package com.infenia.jagratha.plugin.gradle;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for Gradle plugin. */
@AutoConfiguration
public class GradlePluginAutoConfiguration {

  /** Public constructor for PMD. */
  public GradlePluginAutoConfiguration() {
    super();
  }

  /**
   * Provide a GradlePlugin bean if not already present.
   *
   * @return the GradlePlugin
   */
  @Bean
  @ConditionalOnMissingBean
  public GradlePlugin gradlePlugin() {
    return new GradlePlugin();
  }
}
