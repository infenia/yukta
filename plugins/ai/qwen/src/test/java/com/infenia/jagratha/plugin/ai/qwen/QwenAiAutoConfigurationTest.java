package com.infenia.jagratha.plugin.ai.qwen;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(classes = QwenAiAutoConfigurationTest.TestConfig.class)
class QwenAiAutoConfigurationTest {

  @Autowired private ApplicationContext context;

  @Test
  void testAutoConfiguration() {
    assertTrue(context.containsBean("qwenCodePlugin"));
    assertNotNull(context.getBean(QwenCodePlugin.class));
  }

  @SpringBootApplication
  static class TestConfig {}
}
