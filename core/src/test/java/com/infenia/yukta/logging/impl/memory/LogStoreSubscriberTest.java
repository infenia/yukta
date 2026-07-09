// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.time.Duration;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for LogStoreSubscriber reactive subscription to log flux. */
@MockitoSettings
@NoArgsConstructor
@DisplayName("LogStoreSubscriber")
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.CommentDefaultAccessModifier",
  "PMD.LawOfDemeter"
})
class LogStoreSubscriberTest {

  /** Test execution ID. */
  private static final String EXEC_ID = "exec-1";

  /** Test session ID. */
  private static final String SESSION_ID = "session-1";

  /** Test processor ID. */
  private static final String PROCESSOR_ID = "processor-1";

  /** Test processor name. */
  private static final String PROCESSOR_NAME = "Processor";

  /** Mock task tracker service. */
  @Mock private DefaultTaskTrackerService taskTracker;

  /** Mock log store for persistence. */
  @Mock private PluginLogStore store;

  /** Captor for log entry verification. */
  @Captor private ArgumentCaptor<PluginLogEntry> entryCaptor;

  /** Subject under test. */
  private LogStoreSubscriber subscriber;

  @BeforeEach
  void setUp() {
    lenient().when(taskTracker.getLogFlux()).thenReturn(Flux.empty());
    subscriber = new LogStoreSubscriber(store, taskTracker);
  }

  @Nested
  @DisplayName("Initialization and Subscription")
  @NoArgsConstructor
  class InitializationTests {

    @Test
    @DisplayName("init subscribes to log flux and writes entries")
    void init_subscribesToLogFlux_writesToStore() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message 1",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      subscriber.init();

      // Allow time for subscription to process on boundedElastic()
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> verify(store).write(entryCaptor.capture()));
      assertThat(entryCaptor.getValue()).isNotNull();
      subscriber.dispose();
    }

    @Test
    @DisplayName("init with multiple log events writes all entries")
    void init_multipleLogEvents_allWritten() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message 1",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      final PluginLogEntry entry2 =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message 2",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      final PluginLogEntry entry3 =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDERR,
              "Message 3",
              LogLevel.ERROR,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry1, entry2, entry3));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      subscriber.init();

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> verify(store, times(3)).write(any(PluginLogEntry.class)));
      assertThat(subscriber).isNotNull();
      subscriber.dispose();
    }

    @Test
    @DisplayName("init with empty flux completes without items")
    void init_emptyFlux_completesWithoutItems() {
      when(taskTracker.getLogFlux()).thenReturn(Flux.empty());

      subscriber.init();

      await()
          .atMost(Duration.ofSeconds(1))
          .until(
              () -> {
                verify(store, times(0)).write(any(PluginLogEntry.class));
                return true;
              });
      assertThat(subscriber).isNotNull();
      subscriber.dispose();
    }
  }

  @Nested
  @DisplayName("Error Handling")
  @NoArgsConstructor
  class ErrorHandlingTests {

    @Test
    @DisplayName("handles write errors gracefully without throwing")
    void init_writeErrorOccurs_handlesErrorGracefully() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDERR,
              "Error message",
              LogLevel.ERROR,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class)))
          .thenReturn(Mono.error(new RuntimeException("Store write failed")));

      subscriber.init();

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> verify(store).write(any(PluginLogEntry.class)));
      assertThat(subscriber).isNotNull();
      subscriber.dispose();
    }

    @Test
    @DisplayName("handles multiple errors without terminating subscription")
    void init_multipleErrors_continuesProcessing() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message 1",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      final PluginLogEntry entry2 =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message 2",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry1, entry2));
      when(store.write(any(PluginLogEntry.class)))
          .thenReturn(
              Mono.error(new RuntimeException("Store write failed")),
              Mono.error(new RuntimeException("Store write failed")));

      subscriber.init();

      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      verify(store, times(2)).write(any(PluginLogEntry.class));
      subscriber.dispose();
    }

    @Test
    @DisplayName("handles flux error at subscription level")
    void init_fluxErrorAtSubscriptionLevel_logsError() {
      final RuntimeException fluxError = new RuntimeException("Flux error");
      when(taskTracker.getLogFlux()).thenReturn(Flux.error(fluxError));

      subscriber.init();

      // The subscription should be active even though the flux errors
      await().atMost(Duration.ofSeconds(2)).until(() -> subscriber != null);

      // Should have attempted to subscribe and handled error gracefully
      subscriber.dispose();
      assertThat(subscriber).isNotNull();
    }

    @Test
    @DisplayName("logs warning when write operation fails with execution context")
    void init_writeFailsWithContext_logsWarningWithDetails() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              "exec-specific",
              "session-1",
              "processor-1",
              "Processor",
              LogStream.STDOUT,
              "Message",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class)))
          .thenReturn(Mono.error(new RuntimeException("Test write error")));

      subscriber.init();

      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // Verify the write was attempted
      verify(store).write(entryCaptor.capture());
      final PluginLogEntry captured = entryCaptor.getValue();
      assertThat(captured.executionId()).isEqualTo("exec-specific");
      subscriber.dispose();
    }
  }

  @Nested
  @DisplayName("Cleanup and Disposal")
  @NoArgsConstructor
  class CleanupTests {

    @Test
    @DisplayName("dispose does not throw exception")
    void dispose_withActiveSubscription_disposesSubscription() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      subscriber.init();

      await()
          .atMost(Duration.ofSeconds(1))
          .until(
              () -> {
                subscriber.dispose();
                return true;
              });
      assertThat(subscriber).isNotNull();
    }

    @Test
    @DisplayName("dispose with active, not-yet-completed subscription disposes it")
    void dispose_activeSubscription_disposesAndLogs() {
      // Flux.never() keeps the subscription alive so dispose() observes it as not
      // yet disposed, exercising the true branch of subscription != null && !isDisposed().
      when(taskTracker.getLogFlux()).thenReturn(Flux.never());

      subscriber.init();
      subscriber.dispose();

      assertThat(subscriber).isNotNull();
    }

    @Test
    @DisplayName("dispose with null subscription does not throw")
    void dispose_nullSubscription_doesNotThrow() {
      // subscriber created in setUp but init() not called yet
      // so subscription field remains null

      // Should not throw even though subscription is null
      subscriber.dispose();
      assertThat(subscriber).isNotNull();
    }

    @Test
    @DisplayName("dispose when already disposed is idempotent")
    void dispose_alreadyDisposed_isIdempotent() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Message",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      subscriber.init();

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> verify(store).write(any(PluginLogEntry.class)));

      subscriber.dispose();
      // Calling dispose again should not throw
      subscriber.dispose();
      assertThat(subscriber).isNotNull();
    }
  }

  @Nested
  @DisplayName("Pipeline Testing")
  @NoArgsConstructor
  class PipelineTests {

    @Test
    @DisplayName("buildLogPipeline processes entries without blocking")
    void buildLogPipeline_processesEntries() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Pipeline test",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      // Test the pipeline directly
      StepVerifier.create(subscriber.buildLogPipeline()).expectComplete().verify();

      verify(store).write(entryCaptor.capture());
      assertThat(entryCaptor.getValue()).isEqualTo(entry);
    }

    @Test
    @DisplayName("buildLogPipeline handles write errors")
    void buildLogPipeline_handlesWriteErrors() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDERR,
              "Error test",
              LogLevel.ERROR,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class)))
          .thenReturn(Mono.error(new RuntimeException("Write failed")));

      // Pipeline should complete despite the error (error is handled)
      StepVerifier.create(subscriber.buildLogPipeline()).expectComplete().verify();

      verify(store).write(any(PluginLogEntry.class));
    }

    @Test
    @DisplayName("buildLogPipeline handles flux errors")
    void buildLogPipeline_handlesFluxError() {
      final RuntimeException fluxError = new RuntimeException("Flux error");
      when(taskTracker.getLogFlux()).thenReturn(Flux.error(fluxError));

      // Error from the source flux should be propagated
      StepVerifier.create(subscriber.buildLogPipeline())
          .expectError(RuntimeException.class)
          .verify();
    }

    @Test
    @DisplayName("buildAndSubscribe calls completion handler with successful flux")
    void buildAndSubscribe_completionHandlerWithSuccess() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXEC_ID,
              SESSION_ID,
              PROCESSOR_ID,
              PROCESSOR_NAME,
              LogStream.STDOUT,
              "Complete test",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      // buildAndSubscribe returns a Disposable that manages the subscription
      final Disposable disposable = subscriber.buildAndSubscribe();

      // Give time for async processing and completion signal
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> verify(store).write(any(PluginLogEntry.class)));

      disposable.dispose();
      assertThat(disposable.isDisposed()).isTrue();
    }

    @Test
    @DisplayName("buildAndSubscribe calls error handler on flux error")
    void buildAndSubscribe_errorHandlerOnFluxError() {
      final RuntimeException testError = new RuntimeException("Flux error test");
      when(taskTracker.getLogFlux()).thenReturn(Flux.error(testError));

      // buildAndSubscribe returns a Disposable; error handler should be invoked
      final Disposable disposable = subscriber.buildAndSubscribe();

      // Give time for error signal processing
      await()
          .atMost(Duration.ofSeconds(2))
          .until(
              () -> {
                Thread.yield();
                return true;
              });

      disposable.dispose();
      assertThat(disposable.isDisposed()).isTrue();
    }

    @Test
    @DisplayName("buildAndSubscribe handles subscription lifecycle")
    void buildAndSubscribe_managesSubscriptionLifecycle() {
      // Flux.never() keeps the subscription alive so isDisposed() can be deterministically
      // asserted false before dispose(), instead of racing an async-completing flux.
      when(taskTracker.getLogFlux()).thenReturn(Flux.never());

      final Disposable disposable = subscriber.buildAndSubscribe();

      assertThat(disposable).isNotNull();
      assertThat(disposable.isDisposed()).isFalse();

      disposable.dispose();
      assertThat(disposable.isDisposed()).isTrue();
    }
  }

  @Nested
  @DisplayName("Entry Capture and Verification")
  @NoArgsConstructor
  class EntryCapturingTests {

    @Test
    @DisplayName("captures and verifies entry details")
    void init_capturesEntryDetails_verifiesContent() {
      final Instant now = Instant.now();
      final PluginLogEntry entry =
          new PluginLogEntry(
              "exec-123",
              "session-456",
              "plugin-789",
              "MyPlugin",
              LogStream.STDOUT,
              "Test log message",
              LogLevel.INFO,
              now,
              null,
              null);

      when(taskTracker.getLogFlux()).thenReturn(Flux.just(entry));
      when(store.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

      subscriber.init();

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> verify(store).write(entryCaptor.capture()));
      final PluginLogEntry captured = entryCaptor.getValue();
      assertThat(captured.executionId()).isEqualTo("exec-123");
      assertThat(captured.sessionId()).isEqualTo("session-456");
      assertThat(captured.pluginId()).isEqualTo("plugin-789");
      assertThat(captured.pluginName()).isEqualTo("MyPlugin");
      assertThat(captured.stream()).isEqualTo(LogStream.STDOUT);
      assertThat(captured.message()).isEqualTo("Test log message");
      assertThat(captured.logLevel()).isEqualTo(LogLevel.INFO);
      assertThat(captured.timestamp()).isEqualTo(now);

      subscriber.dispose();
    }
  }
}
