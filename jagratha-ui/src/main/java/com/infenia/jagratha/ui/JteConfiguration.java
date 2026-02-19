package com.infenia.jagratha.ui;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for JTE template engine. */
@Configuration
public class JteConfiguration {

  @Value("${jte.usePrecompiledTemplates:false}")
  private boolean usePrecompiled;

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
    final TemplateEngine engine;
    if (usePrecompiled) {
      // This will look for precompiled templates in the classpath
      engine = TemplateEngine.createPrecompiled(ContentType.Html);
    } else {
      // Development mode: load templates from the source directory for hot-reloading
      Path path = Paths.get("jagratha-ui/src/main/jte");
      if (!Files.exists(path)) {
        path = Paths.get("src/main/jte");
      }
      if (!Files.exists(path)) {
        path = Paths.get("../jagratha-ui/src/main/jte");
      }

      final CodeResolver codeResolver = new DirectoryCodeResolver(path);
      engine = TemplateEngine.create(codeResolver, ContentType.Html);
      engine.setBinaryStaticContent(true);
    }
    return engine;
  }
}
