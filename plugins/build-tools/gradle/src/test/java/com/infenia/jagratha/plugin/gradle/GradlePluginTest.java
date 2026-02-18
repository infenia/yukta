package com.infenia.jagratha.plugin.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradlePluginTest {

  private GradlePlugin plugin;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    plugin = new GradlePlugin();
  }

  @Test
  void testGetName() {
    assertEquals("gradle", plugin.getName());
  }

  @Test
  void testIdentifyModuleRoot() {
    String module = plugin.identifyModule(tempDir.toString(), "src/main/java/App.java");
    assertEquals("", module);
  }

  @Test
  void testIdentifyModuleSubproject() throws IOException {
    Path subproject = tempDir.resolve("subproject");
    Files.createDirectories(subproject);
    Files.createFile(subproject.resolve("build.gradle"));

    String module = plugin.identifyModule(tempDir.toString(), "subproject/src/main/java/Lib.java");
    assertEquals(":subproject", module);
  }

  @Test
  void testBuildTaskCommandDefault() {
    List<String> command = plugin.buildTaskCommand(":module", "test", Map.of());
    assertEquals(List.of("./gradlew", ":module:test"), command);
  }

  @Test
  void testBuildTaskCommandCustomGradle() {
    List<String> command =
        plugin.buildTaskCommand("", "build", Map.of("gradlePath", "/usr/bin/gradle"));
    assertEquals(List.of("/usr/bin/gradle", "build"), command);
  }

  @Test
  void testBuildTaskCommandAbsoluteTask() {
    List<String> command = plugin.buildTaskCommand(":module", ":other:task", Map.of());
    assertEquals(List.of("./gradlew", ":other:task"), command);
  }
}
