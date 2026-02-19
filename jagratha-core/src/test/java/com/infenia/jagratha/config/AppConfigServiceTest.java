package com.infenia.jagratha.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.jagratha.model.PluginRegistration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppConfigServiceTest {

  private AppConfigService configService;

  @BeforeEach
  void setUp() {
    configService = new AppConfigService();
  }

  @Test
  void testDefaultValues() {
    String sessionId = "sess-1";
    assertEquals("", configService.getProjectPath(sessionId));
    assertEquals("", configService.getPluginName(sessionId));
    assertEquals(Map.of(), configService.getPluginConfig(sessionId));
    assertTrue(!configService.getTasks(sessionId).isEmpty());
    assertEquals(300L, configService.getExecutionTimeout(sessionId));
    String home = System.getProperty("user.home");
    assertEquals(home + "/.jagratha/modified-files", configService.getFileLogDir(sessionId));
    assertEquals(home + "/.jagratha/results", configService.getResultLogDir(sessionId));
    assertTrue(configService.getWorkflows(sessionId).isEmpty());
  }

  @Test
  void testApiOverrides() {
    String sessionId = "sess-1";
    configService.setProjectPath(sessionId, "/api/path");
    List<PluginRegistration> plugins =
        List.of(new PluginRegistration("gradle", Map.of("gradlePath", "/api/gradle")));
    configService.setPlugins(sessionId, plugins);
    configService.setWorkflows(sessionId, List.of());

    assertEquals("/api/path", configService.getProjectPath(sessionId));
    assertEquals("gradle", configService.getPluginName(sessionId));
    assertEquals(Map.of("gradlePath", "/api/gradle"), configService.getPluginConfig(sessionId));
    assertTrue(configService.getWorkflows(sessionId).isEmpty());

    // Another session should still have defaults
    String otherSession = "sess-2";
    assertEquals("", configService.getProjectPath(otherSession));
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

    configService.setPlugins(
        sess2, List.of(new PluginRegistration("maven", Map.of("mavenPath", "/api/maven"))));
    assertTrue(configService.getActiveSessionIds().contains(sess1));
    assertTrue(configService.getActiveSessionIds().contains(sess2));
    assertEquals(2, configService.getActiveSessionIds().size());
  }
}
