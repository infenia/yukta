package io.jagratha.jagratha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jagratha.jagratha.config.JagrathaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JagrathaConfigTest {

  @Autowired private JagrathaConfig config;

  @Test
  void contextLoads() {
    assertNotNull(config);
    assertNotNull(config.externalProject());
  }

  @Test
  void testConfigValues() {
    // Values from config.yaml
    assertEquals("/tmp/external-project", config.externalProject().path());
    assertEquals("./gradlew", config.externalProject().gradlePath());
  }
}
