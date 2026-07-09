// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.memory;

import com.infenia.yukta.logging.api.ExecutionSummary;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory implementation of PluginLogReader.
 *
 * <p>Reads from shared ConcurrentHashMap storage. Requires InMemoryPluginLogWriter to be used for
 * persistence.
 */
@Slf4j
@RequiredArgsConstructor
public class InMemoryPluginLogReader implements PluginLogReader {

  /** Shared storage reference. */
  private final Map<String, List<PluginLogEntry>> storage;

  @Override
  public Flux<PluginLogEntry> readExecution(final String executionId) {
    return Mono.fromCallable(() -> storage.getOrDefault(executionId, List.of()))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapIterable(entries -> entries)
        .doOnNext(
            entry ->
                log.atTrace()
                    .addKeyValue("executionId", entry.executionId())
                    .addKeyValue("count", 1)
                    .log("Read execution log entry"));
  }

  @Override
  public Flux<PluginLogEntry> readSession(final String sessionId) {
    return Mono.fromCallable(
            () ->
                storage.values().stream()
                    .flatMap(List::stream)
                    .filter(entry -> sessionId.equals(entry.sessionId()))
                    .collect(Collectors.toList()))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapIterable(entries -> entries)
        .doOnNext(
            _ -> log.atTrace().addKeyValue("sessionId", sessionId).log("Read session log entry"));
  }

  @Override
  public Mono<List<ExecutionSummary>> listExecutions(final String sessionId) {
    return Mono.fromCallable(
            () ->
                storage.entrySet().stream()
                    .filter(
                        entry ->
                            !entry.getValue().isEmpty()
                                && sessionId.equals(entry.getValue().getFirst().sessionId()))
                    .map(
                        entry -> {
                          final List<PluginLogEntry> entries = entry.getValue();
                          final PluginLogEntry first = entries.getFirst();
                          final PluginLogEntry last = entries.getLast();
                          final LocalDateTime startTime =
                              ZonedDateTime.ofInstant(first.timestamp(), ZoneId.systemDefault())
                                  .toLocalDateTime();
                          final LocalDateTime endTime =
                              ZonedDateTime.ofInstant(last.timestamp(), ZoneId.systemDefault())
                                  .toLocalDateTime();
                          return new ExecutionSummary(
                              entry.getKey(), sessionId, startTime, endTime, entries.size());
                        })
                    .sorted((a, b) -> b.startTime().compareTo(a.startTime()))
                    .collect(Collectors.toList()))
        .subscribeOn(Schedulers.boundedElastic())
        .doOnNext(
            summaries ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("executionCount", summaries.size())
                    .log("Listed executions"));
  }

  @Override
  public Mono<String> getRawContent(final String executionId) {
    return Mono.fromCallable(
            () ->
                storage.getOrDefault(executionId, List.of()).stream()
                    .map(
                        entry ->
                            String.format(
                                "%s | %s | %s | %s",
                                entry.timestamp(),
                                entry.stream(),
                                entry.pluginId(),
                                entry.message()))
                    .collect(Collectors.joining("\n")))
        .subscribeOn(Schedulers.boundedElastic())
        .doOnNext(
            content ->
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("contentLength", content.length())
                    .log("Retrieved raw log content"));
  }
}
