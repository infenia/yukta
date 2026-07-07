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

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import java.time.Duration;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for InMemoryPluginLogStore.
 *
 * <p>Verifies that the in-memory implementation correctly stores, retrieves, and manages log
 * entries with retention policies.
 */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.TooManyMethods", "PMD.LinguisticNaming"})
class InMemoryPluginLogStoreTest {

  /** Test session identifier. */
  private static final String SESSION_ID = "session-456";

  /** Test processor identifier. */
  private static final String PROCESSOR_ID = "processor-1";

  /** Test processor name. */
  private static final String PROCESSOR_NAME = "Data Processor";

  /** Default retention period in minutes. */
  private static final int DEFAULT_RETENTION_MINUTES = 1;

  /** High retention period in minutes for testing cap behavior. */
  private static final int HIGH_RETENTION_MINUTES = 2000;

  /** Maximum retention period in minutes. */
  private static final int MAX_RETENTION_MINUTES = 1440;

  /** The store being tested. */
  private InMemoryPluginLogStore store;

  @BeforeEach
  void setUp() {
    final PluginLogStoreConfig config = new PluginLogStoreConfig();
    final var retention = config.getRetention();
    retention.setDefaultPeriodMinutes(DEFAULT_RETENTION_MINUTES);
    store = new InMemoryPluginLogStore(config);
  }

  @Test
  void testWriteAndReadLogEntry() {
    final String executionId = "exec-123";
    final PluginLogEntry entry =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Processing started",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    store.write(entry).block();

    StepVerifier.create(store.readExecution(executionId))
        .assertNext(
            read -> {
              assertThat(read.executionId()).isEqualTo(executionId);
              assertThat(read.message()).isEqualTo("Processing started");
              assertThat(read.logLevel()).isEqualTo(LogLevel.INFO);
            })
        .verifyComplete();
  }

  @Test
  void testReadMultipleEntries() {
    final String executionId = "exec-789";
    final PluginLogEntry entry1 =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Line 1",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);
    final PluginLogEntry entry2 =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Line 2",
            LogLevel.WARN,
            Instant.now(),
            null,
            null);

    store.write(entry1).then(store.write(entry2)).block();

    StepVerifier.create(store.readExecution(executionId).collectList())
        .assertNext(
            entries -> {
              assertThat(entries).hasSize(2);
              assertThat(entries.get(0).message()).isEqualTo("Line 1");
              assertThat(entries.get(1).message()).isEqualTo("Line 2");
            })
        .verifyComplete();
  }

  @Test
  void testReadNonExistentExecution() {
    StepVerifier.create(store.readExecution("nonexistent").collectList())
        .assertNext(entries -> assertThat(entries).isEmpty())
        .verifyComplete();
  }

  @Test
  void testCleanupRemovesLogs() {
    final String executionId = "exec-cleanup";
    final PluginLogEntry entry =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "To be deleted",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    StepVerifier.create(
            store
                .write(entry)
                .then(store.cleanup(executionId))
                .thenMany(store.readExecution(executionId).collectList()))
        .assertNext(entries -> assertThat(entries).isEmpty())
        .verifyComplete();
  }

  @Test
  void testEffectiveRetentionIsCapped() {
    final PluginLogStoreConfig configWithHighValue = new PluginLogStoreConfig();
    final var retentionConfig = configWithHighValue.getRetention();
    retentionConfig.setDefaultPeriodMinutes(HIGH_RETENTION_MINUTES);
    final InMemoryPluginLogStore storeWithHighConfig =
        new InMemoryPluginLogStore(configWithHighValue);

    final Duration effective = storeWithHighConfig.getEffectiveRetention();
    assertThat(effective.toMinutes()).isLessThanOrEqualTo(MAX_RETENTION_MINUTES);
  }

  @Test
  @DisplayName("write multiple executions stored separately")
  void write_multipleExecutions_storedSeparately() {
    final String exec1 = "exec-1";
    final String exec2 = "exec-2";

    final PluginLogEntry entry1 =
        new PluginLogEntry(
            exec1,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Exec1 message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    final PluginLogEntry entry2 =
        new PluginLogEntry(
            exec2,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Exec2 message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    store.write(entry1).then(store.write(entry2)).block();

    StepVerifier.create(store.readExecution(exec1).collectList())
        .assertNext(
            entries -> {
              assertThat(entries).hasSize(1);
              assertThat(entries.getFirst().message()).isEqualTo("Exec1 message");
            })
        .verifyComplete();

    StepVerifier.create(store.readExecution(exec2).collectList())
        .assertNext(
            entries -> {
              assertThat(entries).hasSize(1);
              assertThat(entries.getFirst().message()).isEqualTo("Exec2 message");
            })
        .verifyComplete();
  }

  @Test
  @DisplayName("readExecution returns defensive copy")
  void readExecution_returnsCopy_notReference() {
    final String executionId = "exec-copy-test";

    final PluginLogEntry entry =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Test message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    store.write(entry).block();

    final var firstRead = store.readExecution(executionId).collectList().block();
    final var secondRead = store.readExecution(executionId).collectList().block();

    assertThat(firstRead).isNotSameAs(secondRead);
    assertThat(firstRead).hasSameSizeAs(secondRead);
  }

  @Test
  @DisplayName("cleanup removes logs and read returns empty")
  void cleanup_afterInvalidate_readReturnsEmpty() {
    final String executionId = "exec-cleanup-test";

    final PluginLogEntry entry =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "To clean",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    store.write(entry).then(store.cleanup(executionId)).block();

    StepVerifier.create(store.readExecution(executionId).collectList())
        .assertNext(entries -> assertThat(entries).isEmpty())
        .verifyComplete();
  }

  @Test
  @DisplayName("getEffectiveRetention returns configured duration")
  void getEffectiveRetention_returnsConfiguredDuration() {
    final Duration effective = store.getEffectiveRetention();
    assertThat(effective).isNotNull();
    assertThat(effective.toMinutes()).isPositive();
  }

  @Test
  @DisplayName("multiple writes to same execution appended")
  void writeMultiple_sameExecution_appended() {
    final String executionId = "exec-append";

    final PluginLogEntry entry1 =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "First",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    final PluginLogEntry entry2 =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDOUT,
            "Second",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    final PluginLogEntry entry3 =
        new PluginLogEntry(
            executionId,
            SESSION_ID,
            PROCESSOR_ID,
            PROCESSOR_NAME,
            LogStream.STDERR,
            "Third",
            LogLevel.ERROR,
            Instant.now(),
            null,
            null);

    store.write(entry1).then(store.write(entry2)).then(store.write(entry3)).block();

    StepVerifier.create(store.readExecution(executionId).collectList())
        .assertNext(
            entries -> {
              assertThat(entries).hasSize(3);
              assertThat(entries.get(0).message()).isEqualTo("First");
              assertThat(entries.get(1).message()).isEqualTo("Second");
              assertThat(entries.get(2).message()).isEqualTo("Third");
            })
        .verifyComplete();
  }
}
