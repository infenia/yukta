package com.infenia.jagratha.plugin.processor.checkstyle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for Checkstyle processor plugin. */
@AutoConfiguration
public class CheckstyleProcessorAutoConfiguration {

  /** Public constructor for PMD. */
  public CheckstyleProcessorAutoConfiguration() {
    super();
  }

  /**
   * Provide a CheckstyleXmlProcessor bean if not already present and ObjectMapper is available.
   *
   * @param objectMapper the ObjectMapper
   * @return the CheckstyleXmlProcessor
   */
  @Bean
  @ConditionalOnBean(ObjectMapper.class)
  @ConditionalOnMissingBean
  public CheckstyleXmlProcessor checkstyleXmlProcessor(final ObjectMapper objectMapper) {
    return new CheckstyleXmlProcessor(objectMapper);
  }
}
