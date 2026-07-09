// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
