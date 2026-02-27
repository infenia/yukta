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
package com.infenia.yukta.plugin.gradle;

import com.infenia.yukta.plugin.buildtool.AbstractBuildToolPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Gradle implementation of a build tool plugin. */
public class GradlePlugin extends AbstractBuildToolPlugin {

  /** Public constructor. */
  public GradlePlugin() {
    super();
  }

  @Override
  public String getType() {
    return "gradle";
  }

  @Override
  public String getDescription() {
    return "Executes Gradle tasks on a project and returns the aggregated output.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- projectRoot: Absolute path to the Gradle project.\n"
        + "- tasks: List of strings representing Gradle tasks to run (e.g., ['build', 'test']). "
        + "Default is ['check'].\n"
        + "- gradlePath: Path to the gradle executable (default: './gradlew').\n"
        + "- timeout: Maximum execution time in seconds (default: 600).";
  }

  @Override
  protected List<String> getTasks(final Map<String, Object> config) {
    @SuppressWarnings("unchecked")
    final List<String> tasks = (List<String>) config.getOrDefault("tasks", List.of("check"));
    return tasks;
  }

  @Override
  protected String getProjectRoot(final Map<String, Object> config) {
    return (String) config.get("projectRoot");
  }

  @Override
  protected List<String> getCommand(final Map<String, Object> config, final String task) {
    final String gradlePath = (String) config.getOrDefault("gradlePath", "./gradlew");
    final List<String> command = new ArrayList<>();
    command.add(gradlePath);
    command.add(task);
    return command;
  }

  @Override
  protected long getTimeout(final Map<String, Object> config) {
    return ((Number) config.getOrDefault("timeoutSeconds", config.getOrDefault("timeout", 600L)))
        .longValue();
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final Mono<Void> result;
    if (config == null) {
      result = Mono.error(new IllegalArgumentException("Configuration is required"));
    } else if (config.containsKey("projectRoot")) {
      final Object tasks = config.get("tasks");
      if (tasks == null || tasks instanceof List) {
        result = Mono.empty();
      } else {
        result = Mono.error(new IllegalArgumentException("tasks must be a list of strings"));
      }
    } else {
      result = Mono.error(new IllegalArgumentException("projectRoot is required"));
    }
    return result;
  }
}
