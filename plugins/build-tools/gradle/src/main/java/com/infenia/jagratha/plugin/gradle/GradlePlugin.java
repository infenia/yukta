/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.plugin.gradle;

import com.infenia.jagratha.plugin.JagrathaPlugin;
import com.infenia.jagratha.plugin.ValidationResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Gradle plugin implementation. */
@Slf4j
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

  @Override
  public ValidationResult validateConfig(final Map<String, Object> config) {
    final ValidationResult result;
    if (config == null) {
      result = ValidationResult.error("Configuration is required");
    } else {
      final Object gradlePath = config.get("gradlePath");
      if (gradlePath != null && !(gradlePath instanceof String)) {
        result =
            ValidationResult.error(
                "Invalid configuration",
                List.of(new ValidationResult.FieldError("gradlePath", "Must be a string")));
      } else {
        result = ValidationResult.success();
      }
    }
    return result;
  }
}
