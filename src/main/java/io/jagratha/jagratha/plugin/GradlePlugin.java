package io.jagratha.jagratha.plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Gradle plugin implementation. */
@Slf4j
@Component
public class GradlePlugin implements JagrathaPlugin {

  /** Public constructor for PMD. */
  public GradlePlugin() {
    super();
  }

  @Override
  public String getName() {
    return "gradle";
  }

  @Override
  public String identifyModule(final String projectRoot, final String relativePath) {
    String result = "";
    try {
      final Path rootPath = Path.of(projectRoot).toAbsolutePath().normalize();
      final Path fileAbsPath = rootPath.resolve(relativePath).toAbsolutePath().normalize();

      Path current = fileAbsPath.getParent();
      while (current != null && current.startsWith(rootPath)) {
        if (Files.exists(current.resolve("build.gradle"))
            || Files.exists(current.resolve("build.gradle.kts"))) {
          final Path relPath = rootPath.relativize(current);
          final String modulePath = relPath.toString();
          if (!modulePath.isEmpty()) {
            result = ":" + modulePath.replace(File.separator, ":");
          }
          break;
        }
        current = current.getParent();
      }
    } catch (InvalidPathException e) {
      if (log.isWarnEnabled()) {
        log.warn("Failed to identify module for path: {}", relativePath, e);
      }
    }
    return result;
  }

  @Override
  public List<String> buildTaskCommand(
      final String module, final String task, final Map<String, Object> pluginConfig) {
    final String gradlePath = (String) pluginConfig.get("gradlePath");
    final List<String> command = new ArrayList<>();
    command.add(gradlePath != null && !gradlePath.isEmpty() ? gradlePath : "./gradlew");

    if (module.isEmpty()) {
      command.add(task);
    } else if (task.startsWith(":")) {
      command.add(task);
    } else {
      command.add(module + ":" + task);
    }
    return command;
  }
}
