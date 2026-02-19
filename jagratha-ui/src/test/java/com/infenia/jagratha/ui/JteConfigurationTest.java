package com.infenia.jagratha.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import gg.jte.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = JteConfiguration.class)
@TestPropertySource(properties = "jte.usePrecompiledTemplates=false")
class JteConfigurationTest {

  @Autowired private TemplateEngine templateEngine;

  @Test
  void testTemplateEngineBean() {
    assertNotNull(templateEngine);
  }
}
