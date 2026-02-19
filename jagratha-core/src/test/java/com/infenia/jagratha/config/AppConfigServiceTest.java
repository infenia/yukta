package com.infenia.jagratha.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    String sessionId = "sess-1";
    assertEquals("/static/path", configService.getProjectPath(sessionId));
    assertEquals("gradle", configService.getPluginName(sessionId));
    assertEquals(Map.of("gradlePath", "/static/gradle"), configService.getPluginConfig(sessionId));
    assertEquals(List.of("staticTask"), configService.getTasks(sessionId));
    assertEquals(100L, configService.getExecutionTimeout(sessionId));
    assertEquals("/static/files", configService.getFileLogDir(sessionId));
    assertEquals("/static/results", configService.getResultLogDir(sessionId));
    assertTrue(configService.getWorkflows(sessionId).isEmpty());
  }

  @Test
  void testApiOverrides() {
    String sessionId = "sess-1";
    configService.setProjectPath(sessionId, "/api/path");
    configService.setPluginName(sessionId, "maven");
    configService.setPluginConfig(sessionId, Map.of("mavenPath", "/api/maven"));
    configService.setTasks(sessionId, List.of("apiTask"));
    configService.setExecutionTimeout(sessionId, 200L);
    configService.setFileLogDir(sessionId, "/api/files");
    configService.setResultLogDir(sessionId, "/api/results");
    configService.setWorkflows(sessionId, List.of());

    assertEquals("/api/path", configService.getProjectPath(sessionId));
    assertEquals("maven", configService.getPluginName(sessionId));
    assertEquals(Map.of("mavenPath", "/api/maven"), configService.getPluginConfig(sessionId));
    assertEquals(List.of("apiTask"), configService.getTasks(sessionId));
    assertEquals(200L, configService.getExecutionTimeout(sessionId));
    assertEquals("/api/files", configService.getFileLogDir(sessionId));
    assertEquals("/api/results", configService.getResultLogDir(sessionId));
    assertTrue(configService.getWorkflows(sessionId).isEmpty());

    // Another session should still have defaults
    String otherSession = "sess-2";
    assertEquals("/static/path", configService.getProjectPath(otherSession));
  }

  @Test
  void testNullSessionId() {
    // Should fallback to static config
    assertEquals("/static/path", configService.getProjectPath(null));
    assertEquals("gradle", configService.getPluginName(null));
    assertEquals(Map.of("gradlePath", "/static/gradle"), configService.getPluginConfig(null));
    assertEquals(List.of("staticTask"), configService.getTasks(null));
    assertEquals(100L, configService.getExecutionTimeout(null));
    assertEquals("/static/files", configService.getFileLogDir(null));
    assertEquals("/static/results", configService.getResultLogDir(null));
  }

  @Test
  void testClearOverrides() {
    String sessionId = "sess-1";
    configService.setPluginConfig(sessionId, Map.of("k", "v"));
    assertEquals(Map.of("k", "v"), configService.getPluginConfig(sessionId));

    configService.setPluginConfig(sessionId, null);
    assertEquals(Map.of("gradlePath", "/static/gradle"), configService.getPluginConfig(sessionId));

    configService.setTasks(sessionId, List.of("t1"));
    assertEquals(List.of("t1"), configService.getTasks(sessionId));
    configService.setTasks(sessionId, null);
    assertEquals(List.of("staticTask"), configService.getTasks(sessionId));

    configService.setWorkflows(sessionId, List.of());
    configService.setWorkflows(sessionId, null);
    assertTrue(configService.getWorkflows(sessionId).isEmpty());
  }

  @Test
  void testPartialApiOverrides() {
    String sessionId = "sess-1";
    configService.setProjectPath(sessionId, "/api/path");

    assertEquals("/api/path", configService.getProjectPath(sessionId));
    assertEquals(
        Map.of("gradlePath", "/static/gradle"),
        configService.getPluginConfig(sessionId)); // still static
  }

  @Test
  void testFallbacks() {
    AppConfig emptyConfig = new AppConfig(null, null, null, null, null, null, null);
    AppConfigService emptyService = new AppConfigService(emptyConfig);
    String sessionId = "sess-1";

    assertEquals("", emptyService.getProjectPath(sessionId));
    assertEquals(300L, emptyService.getExecutionTimeout(sessionId));
    String home = System.getProperty("user.home");
    assertEquals(home + "/.jagratha/modified-files", emptyService.getFileLogDir(sessionId));
    assertEquals(home + "/.jagratha/results", emptyService.getResultLogDir(sessionId));
  }

  @Test
  void testSetNullValues() {
    String sessionId = "sess-1";
    configService.setProjectPath(sessionId, null); // should not change anything
    assertEquals("/static/path", configService.getProjectPath(sessionId));

    configService.setPluginName(sessionId, null);
    assertEquals("gradle", configService.getPluginName(sessionId));

    configService.setExecutionTimeout(sessionId, null);
    assertEquals(100L, configService.getExecutionTimeout(sessionId));

    configService.setFileLogDir(sessionId, null);
    assertEquals("/static/files", configService.getFileLogDir(sessionId));

    configService.setResultLogDir(sessionId, null);
    assertEquals("/static/results", configService.getResultLogDir(sessionId));
  }

  @Test
  void testActiveSessionTracking() {
    String sess1 = "sess-1";
    String sess2 = "sess-2";

    assertTrue(configService.getActiveSessionIds().isEmpty());
    assertTrue(!configService.isActive(sess1));

    configService.setProjectPath(sess1, "/path/1");
    assertTrue(configService.getActiveSessionIds().contains(sess1));
    assertTrue(configService.isActive(sess1));
    assertTrue(!configService.isActive(sess2));

    configService.setPluginName(sess2, "maven");
    assertTrue(configService.getActiveSessionIds().contains(sess1));
    assertTrue(configService.getActiveSessionIds().contains(sess2));
    assertEquals(2, configService.getActiveSessionIds().size());
  }
}
