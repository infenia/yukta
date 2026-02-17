package io.jagratha.jagratha.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JagrathaConfigServiceTest {

  private JagrathaConfig staticConfig;
  private JagrathaConfigService configService;

  @BeforeEach
  void setUp() {
    staticConfig =
        new JagrathaConfig(
            new JagrathaConfig.ExternalProject("/static/path", "/static/gradle"),
            List.of("staticTask"),
            100L,
            new JagrathaConfig.Logs("/static/files", "/static/results"));
    configService = new JagrathaConfigService(staticConfig);
  }

  @Test
  void testDefaultValues() {
    assertEquals("/static/path", configService.getProjectPath());
    assertEquals("/static/gradle", configService.getGradlePath());
    assertEquals(List.of("staticTask"), configService.getTasks());
    assertEquals(100L, configService.getExecutionTimeout());
    assertEquals("/static/files", configService.getFileLogDir());
    assertEquals("/static/results", configService.getResultLogDir());
  }

  @Test
  void testApiOverrides() {
    configService.setProjectPath("/api/path");
    configService.setGradlePath("/api/gradle");
    configService.setTasks(List.of("apiTask"));
    configService.setExecutionTimeout(200L);
    configService.setFileLogDir("/api/files");
    configService.setResultLogDir("/api/results");

    assertEquals("/api/path", configService.getProjectPath());
    assertEquals("/api/gradle", configService.getGradlePath());
    assertEquals(List.of("apiTask"), configService.getTasks());
    assertEquals(200L, configService.getExecutionTimeout());
    assertEquals("/api/files", configService.getFileLogDir());
    assertEquals("/api/results", configService.getResultLogDir());
  }

  @Test
  void testPartialApiOverrides() {
    configService.setProjectPath("/api/path");

    assertEquals("/api/path", configService.getProjectPath());
    assertEquals("/static/gradle", configService.getGradlePath()); // still static
  }
}
