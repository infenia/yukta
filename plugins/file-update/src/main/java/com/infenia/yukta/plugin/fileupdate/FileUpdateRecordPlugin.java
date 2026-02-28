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
package com.infenia.yukta.plugin.fileupdate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.TerminalPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Plugin to record and update file status on the filesystem. */
@Slf4j
public class FileUpdateRecordPlugin implements TerminalPlugin {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, ReentrantLock> locks = new java.util.concurrent.ConcurrentHashMap<>();

  /** Default constructor. */
  public FileUpdateRecordPlugin() {
    super();
  }

  @Override
  public Duration getDefaultTimeout() {
    return Duration.ofSeconds(30);
  }

  @Override
  public String getDescription() {
    return "Records and updates the status of processed files in a JSON file.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- outputDir: Directory where the 'file-status.json' file will be stored.\n"
        + "Expects input messages with a Map payload containing 'path' and 'status' keys.";
  }

  @Override
  public String getType() {
    return "file-update-record";
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String outputDir = (String) config.get("outputDir");
    final Mono<Void> result;
    if (outputDir == null || outputDir.isEmpty()) {
      result = Mono.error(new IllegalArgumentException("outputDir is required in plugin config"));
    } else {
      result =
          input
              .flatMap(
                  msg -> {
                    if (msg.getPayload() instanceof Map payload) {
                      final String path = (String) payload.get("path");
                      final String status = (String) payload.get("status");
                      if (path != null && status != null) {
                        return recordStatus(outputDir, path, status);
                      }
                    }
                    return Mono.empty();
                  })
              .then();
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Mono<Void> recordStatus(
      final String outputDir, final String filePath, final String status) {
    return Mono.fromRunnable(
            () -> {
              final ReentrantLock lock = locks.computeIfAbsent(outputDir, k -> new ReentrantLock());
              lock.lock();
              try {
                final Path dir = Path.of(outputDir);
                Files.createDirectories(dir);
                final Path recordFile = dir.resolve("file-status.json");

                final Map<String, String> statusMap;
                if (Files.exists(recordFile)) {
                  statusMap =
                      objectMapper.readValue(Files.readString(recordFile), LinkedHashMap.class);
                } else {
                  statusMap = new LinkedHashMap<>();
                }

                statusMap.put(filePath, status);
                Files.writeString(recordFile, objectMapper.writeValueAsString(statusMap));
              } catch (IOException e) {
                log.error("Failed to record file status", e);
              } finally {
                lock.unlock();
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }
}
