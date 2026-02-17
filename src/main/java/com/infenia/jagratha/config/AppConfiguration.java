package com.infenia.jagratha.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for the application. */
@Configuration
public class AppConfiguration {

  /** Public constructor for PMD. */
  public AppConfiguration() {
    super();
  }

  /**
   * Provide an ObjectMapper bean if not already present.
   *
   * @return the ObjectMapper
   */
  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
