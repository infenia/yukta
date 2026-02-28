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
package com.infenia.yukta.plugin.buildtool;

import com.infenia.yukta.plugin.BuildGateway;
import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.PluginCategory;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.TriggerPlugin;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for build tool plugins (Gradle, Maven, NPM, etc.). Supports both Trigger and
 * Processor modes.
 */
@Slf4j
public abstract class AbstractBuildToolPlugin implements TriggerPlugin, ProcessorPlugin {

  @Autowired protected BuildGateway buildGateway;

  /** Default constructor. */
  protected AbstractBuildToolPlugin() {
    super();
  }

  @Override
  public PluginCategory getCategory() {
    return PluginCategory.PROCESSOR;
  }

  @Override
  public Duration getDefaultTimeout() {
    return Duration.ofSeconds(600);
  }

  /**
   * Get the list of tasks to execute.
   *
   * @param config the plugin configuration
   * @return the list of tasks
   */
  protected abstract List<String> getTasks(Map<String, Object> config);

  /**
   * Get the project root directory.
   *
   * @param config the plugin configuration
   * @return the project root path
   */
  protected abstract String getProjectRoot(Map<String, Object> config);

  /**
   * Get the command to execute for a given task.
   *
   * @param config the plugin configuration
   * @param task the task to run
   * @return the command as a list of strings
   */
  protected abstract List<String> getCommand(Map<String, Object> config, String task);

  /**
   * Get the timeout for the build tool execution.
   *
   * @param config the plugin configuration
   * @return the timeout in seconds
   */
  protected abstract long getTimeout(Map<String, Object> config);

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    return execute(config, UUID.randomUUID());
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input
        .onBackpressureDrop()
        .flatMap(
            message -> {
              final String traceIdStr = message.getTraceId();
              final UUID traceId =
                  traceIdStr != null ? UUID.fromString(traceIdStr) : UUID.randomUUID();
              return execute(config, traceId);
            },
            1);
  }

  private Flux<Message<?>> execute(final Map<String, Object> config, final UUID traceId) {
    final String projectRoot = getProjectRoot(config);
    final List<String> tasks = getTasks(config);
    final long timeout = getTimeout(config);
    final File projectDir = new File(projectRoot);

    return Flux.fromIterable(tasks)
        .concatMap(task -> executeTask(projectDir, getCommand(config, task), task, timeout))
        .collectList()
        .map(logsList -> String.join("\n--- Task Divider ---\n", logsList))
        .<Message<?>>map(allLogs -> DefaultMessage.create(traceId, allLogs))
        .flux();
  }

  private Mono<String> executeTask(
      final File projectDir, final List<String> command, final String task, final long timeout) {
    return buildGateway.executeTask(projectDir, command, task, timeout);
  }
}
