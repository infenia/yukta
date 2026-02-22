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

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.TriggerPlugin;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Gradle implementation of a TriggerPlugin. */
@Slf4j
public class GradlePlugin implements TriggerPlugin {

  /** Public constructor. */
  public GradlePlugin() {
    super();
  }

  @Override
  public String getType() {
    return "gradle";
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

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Flux<Message> start(final Map<String, Object> config, final Map<String, Object> payload) {
    final String projectRoot = (String) config.get("projectRoot");
    @SuppressWarnings("unchecked")
    final List<String> tasks = (List<String>) config.getOrDefault("tasks", List.of("check"));
    final String gradlePath = (String) config.getOrDefault("gradlePath", "./gradlew");
    final Long timeout = ((Number) config.getOrDefault("timeout", 600L)).longValue();

    final File projectDir = new File(projectRoot);
    final UUID traceId = UUID.randomUUID();

    return Flux.fromIterable(tasks)
        .flatMap(task -> executeTask(projectDir, gradlePath, task, timeout, traceId));
  }

  private Flux<Message> executeTask(
      final File projectDir,
      final String gradlePath,
      final String task,
      final long timeout,
      final UUID traceId) {
    final List<String> command = new ArrayList<>();
    command.add(gradlePath);
    command.add(task);

    return Mono.fromCallable(
            () -> {
              final ProcessBuilder processBuilder = new ProcessBuilder(command);
              processBuilder.directory(projectDir);
              processBuilder.redirectErrorStream(true);
              return processBuilder.start();
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(
            process -> {
              final Flux<String> outputFlux =
                  DataBufferUtils.readInputStream(
                          process::getInputStream, DefaultDataBufferFactory.sharedInstance, 4096)
                      .transform(
                          flux -> StringDecoder.textPlainOnly().decode(flux, null, null, Map.of()));

              final Mono<Integer> exitCodeMono =
                  Mono.fromFuture(process.onExit()).map(Process::exitValue);

              return outputFlux
                  .map(line -> Message.create(traceId, line))
                  .concatWith(
                      exitCodeMono.flatMap(
                          code -> {
                            if (code != 0 && log.isWarnEnabled()) {
                              log.warn("Task {} failed with exit code {}", task, code);
                            }
                            return Mono.empty();
                          }))
                  .timeout(Duration.ofSeconds(timeout))
                  .onErrorResume(
                      TimeoutException.class,
                      e -> {
                        process.destroyForcibly();
                        return Mono.error(new TimeoutException("Timeout running task: " + task));
                      });
            })
        .onErrorResume(
            IOException.class,
            e ->
                Mono.error(
                    new RuntimeException("Error executing task " + task + ": " + e.getMessage())));
  }
}
