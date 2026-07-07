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
package com.infenia.yukta.logging.impl.memory;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory implementation of PluginLogWriter.
 *
 * <p>Stores logs in ConcurrentHashMap indexed by execution ID. Suitable for development and unit
 * testing. Not recommended for production as logs are not persisted.
 */
@Getter(AccessLevel.PACKAGE)
@Slf4j
@RequiredArgsConstructor
public class InMemoryPluginLogWriter implements PluginLogWriter {

  /** Shared storage for log entries indexed by executionId. */
  private final Map<String, List<PluginLogEntry>> storage;

  @Override
  public Mono<Void> write(final PluginLogEntry entry) {
    return writeBatch(List.of(entry));
  }

  @Override
  public Mono<Void> writeBatch(final List<PluginLogEntry> entries) {
    return Mono.fromRunnable(
            () -> {
              final var entriesByExecution =
                  entries.stream()
                      .collect(
                          Collectors.groupingBy(
                              PluginLogEntry::executionId,
                              Collectors.toCollection(ArrayList::new)));
              entriesByExecution.forEach(
                  (executionId, execEntries) ->
                      storage.merge(
                          executionId,
                          execEntries,
                          (existing, newEntries) -> {
                            final List<PluginLogEntry> merged =
                                new ArrayList<>(existing.size() + newEntries.size());
                            merged.addAll(existing);
                            merged.addAll(newEntries);
                            return merged;
                          }));
              log.atTrace()
                  .addKeyValue("entriesCount", entries.size())
                  .log("Wrote plugin log batch to memory");
            })
        .subscribeOn(Schedulers.parallel())
        .then();
  }

  @Override
  public Mono<Void> close() {
    return Mono.fromRunnable(() -> log.atDebug().log("Closed in-memory plugin log writer"));
  }
}
