package com.infenia.jagratha.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppConfigServiceTest {

  private AppConfig staticConfig;
  private AppConfigService configService;

  @BeforeEach
  void setUp() {
    staticConfig =
        new AppConfig(
            new AppConfig.ExternalProject("/static/path"),
            "gradle",
            Map.of("gradlePath", "/static/gradle"),
            List.of("staticTask"),
            List.of(),
            100L,
            new AppConfig.Logs("/static/files", "/static/results"));
    configService = new AppConfigService(staticConfig);
  }

  @Test
  void testDefaultValues() {
    assertEquals("/static/path", configService.getProjectPath());
    assertEquals("gradle", configService.getPluginName());
    assertEquals(Map.of("gradlePath", "/static/gradle"), configService.getPluginConfig());
    assertEquals(List.of("staticTask"), configService.getTasks());
    assertEquals(100L, configService.getExecutionTimeout());
    assertEquals("/static/files", configService.getFileLogDir());
    assertEquals("/static/results", configService.getResultLogDir());
  }

  @Test
  void testApiOverrides() {
    configService.setProjectPath("/api/path");
    configService.setPluginName("maven");
    configService.setPluginConfig(Map.of("mavenPath", "/api/maven"));
    configService.setTasks(List.of("apiTask"));
    configService.setExecutionTimeout(200L);
    configService.setFileLogDir("/api/files");
    configService.setResultLogDir("/api/results");

    assertEquals("/api/path", configService.getProjectPath());
    assertEquals("maven", configService.getPluginName());
    assertEquals(Map.of("mavenPath", "/api/maven"), configService.getPluginConfig());
    assertEquals(List.of("apiTask"), configService.getTasks());
    assertEquals(200L, configService.getExecutionTimeout());
    assertEquals("/api/files", configService.getFileLogDir());
    assertEquals("/api/results", configService.getResultLogDir());
  }

  @Test
  void testPartialApiOverrides() {
    configService.setProjectPath("/api/path");

    assertEquals("/api/path", configService.getProjectPath());
    assertEquals(
        Map.of("gradlePath", "/static/gradle"), configService.getPluginConfig()); // still static
  }

  @Test
  void testFallbacks() {
    AppConfig emptyConfig = new AppConfig(null, null, null, null, null, null, null);
    AppConfigService emptyService = new AppConfigService(emptyConfig);

    assertEquals(300L, emptyService.getExecutionTimeout());
    String home = System.getProperty("user.home");
    assertEquals(home + "/.jagratha/modified-files", emptyService.getFileLogDir());
    assertEquals(home + "/.jagratha/results", emptyService.getResultLogDir());
  }
}
