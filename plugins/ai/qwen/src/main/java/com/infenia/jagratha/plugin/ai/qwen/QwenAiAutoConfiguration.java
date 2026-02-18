package com.infenia.jagratha.plugin.ai.qwen;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for Qwen AI plugin. */
@AutoConfiguration
public class QwenAiAutoConfiguration {

  /** Public constructor for PMD. */
  public QwenAiAutoConfiguration() {
    super();
  }

  /**
   * Provide a QwenCodePlugin bean if not already present.
   *
   * @return the QwenCodePlugin
   */
  @Bean
  @ConditionalOnMissingBean
  public QwenCodePlugin qwenCodePlugin() {
    return new QwenCodePlugin();
  }
}
