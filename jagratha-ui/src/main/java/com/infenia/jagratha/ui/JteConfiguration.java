package com.infenia.jagratha.ui;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for JTE template engine. */
@Configuration
public class JteConfiguration {

  /** Default constructor. */
  public JteConfiguration() {
    // Standard configuration initialization
  }

  /**
   * Create the TemplateEngine bean.
   *
   * @return the template engine
   */
  @Bean
  public TemplateEngine templateEngine() {
    // This will look for precompiled templates in the classpath
    return TemplateEngine.createPrecompiled(ContentType.Html);
  }
}
