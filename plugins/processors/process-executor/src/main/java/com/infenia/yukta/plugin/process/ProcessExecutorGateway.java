// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Gateway for executing external processes with reactive streaming output. */
@Slf4j
@Service
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods"})
@NoArgsConstructor
public class ProcessExecutorGateway {

  /** Conversion factor from nanoseconds to milliseconds. */
  private static final long NANOS_PER_MILLI = 1_000_000L;

  /** Grace period for a destroyed process to exit before it is forcibly killed. */
  private static final Duration FORCE_DESTROY_GRACE = Duration.ofSeconds(2);

  /**
   * Execute a process described by the given specification and return its full outcome.
   *
   * <p>This is the non-throwing core API: a non-zero exit code and a timeout are reported as data
   * on the {@link ProcessExecutionResult} instead of as errors, so callers can route on the exit
   * code. The returned Mono errors only for an invalid specification ({@link
   * IllegalArgumentException}), a process that cannot be started (e.g. {@link IOException} for an
   * unknown command), or unexpected internal failures.
   *
   * <p><strong>MEMORY:</strong> captured output is buffered in memory. Use {@link
   * ProcessExecutionSpec#maxOutputLines()} / {@link ProcessExecutionSpec#maxOutputBytes()} to cap
   * retention for processes with large outputs; excess output is drained but discarded and the
   * result is flagged as truncated.
   *
   * @param spec the execution specification
   * @return a Mono emitting the execution result
   */
  public Mono<ProcessExecutionResult> executeForResult(final ProcessExecutionSpec spec) {
    if (spec == null) {
      return Mono.error(new IllegalArgumentException("spec is mandatory"));
    }
    if (spec.command().isEmpty()) {
      return Mono.error(new IllegalArgumentException("command must not be empty"));
    }
    final List<String> actualCommand =
        spec.useShell() ? wrapInShell(spec.command()) : spec.command();

    return Mono.fromSupplier(System::nanoTime)
        .flatMap(startNanos -> runProcess(spec, actualCommand, startNanos));
  }

  /**
   * Execute a process and return the output as a stream of strings with newlines preserved.
   *
   * <p>Unlike {@link #executeForResult(ProcessExecutionSpec)}, this API signals failures as errors:
   * a non-zero exit code or a timeout produces a {@link WorkflowExecutionException}.
   *
   * @param command the command and arguments to execute
   * @param workingDir the working directory (null defaults to current directory)
   * @param timeoutSeconds the timeout in seconds (must be positive)
   * @param env custom environment variables
   * @param useShell whether to execute the command via a shell
   * @return a Flux containing the process output lines with newlines preserved
   * @throws IllegalArgumentException if timeoutSeconds is not positive
   */
  public Flux<String> executeStream(
      final List<String> command,
      final String workingDir,
      final long timeoutSeconds,
      final Map<String, String> env,
      final boolean useShell) {
    if (timeoutSeconds <= 0) {
      return Flux.error(
          new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds));
    }
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(command)
            .workingDir(workingDir)
            .timeoutSeconds(timeoutSeconds)
            .env(env)
            .useShell(useShell)
            .build();
    return executeForResult(spec).flatMapMany(result -> toLineStream(result, spec));
  }

  /**
   * Execute a process with the given command, working directory, and timeout.
   *
   * <p><strong>MEMORY WARNING:</strong> This method buffers the entire process output in memory.
   * For processes generating large outputs (>100MB), consider using {@link
   * #executeForResult(ProcessExecutionSpec)} with output caps instead to avoid Out-of-Memory
   * errors.
   *
   * @param command the command and arguments to execute
   * @param workingDir the working directory (null defaults to current directory)
   * @param timeoutSeconds the timeout in seconds
   * @return a Mono containing the process output with newlines preserved between lines
   */
  public Mono<String> execute(
      final List<String> command, final String workingDir, final long timeoutSeconds) {
    return executeStream(command, workingDir, timeoutSeconds, Map.of(), false)
        .collectList()
        // Restore newlines between lines that were stripped by BufferedReader.lines()
        .map(list -> String.join("\n", list))
        .onErrorMap(
            e -> {
              if (e instanceof WorkflowExecutionException) {
                return e;
              }
              return new WorkflowExecutionException(
                  "Process execution failed: " + e.getMessage(), e);
            });
  }

  /**
   * Run the process lifecycle: start, capture output, await exit, and clean up.
   *
   * @param spec the execution specification
   * @param actualCommand the command after optional shell wrapping
   * @param startNanos the execution start timestamp in nanoseconds
   * @return a Mono emitting the execution result
   */
  private Mono<ProcessExecutionResult> runProcess(
      final ProcessExecutionSpec spec, final List<String> actualCommand, final long startNanos) {
    return Mono.usingWhen(
            startProcess(spec, actualCommand),
            process -> collectResult(process, spec, startNanos),
            this::cleanupProcess)
        .timeout(Duration.ofSeconds(spec.timeoutSeconds()))
        .onErrorResume(
            TimeoutException.class, _ -> timedOutResult(spec, actualCommand, startNanos));
  }

  /**
   * Start the process described by the specification.
   *
   * @param spec the execution specification
   * @param actualCommand the command after optional shell wrapping
   * @return a Mono emitting the started process
   */
  private Mono<Process> startProcess(
      final ProcessExecutionSpec spec, final List<String> actualCommand) {
    return Mono.fromCallable(
            () -> {
              log.atDebug().setMessage("Starting process: {}").addArgument(actualCommand).log();
              final ProcessBuilder processBuilder = new ProcessBuilder(actualCommand);
              if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
                processBuilder.directory(new File(spec.workingDir()));
                log.atDebug()
                    .setMessage("Process working directory set to: {}")
                    .addArgument(spec.workingDir())
                    .log();
              }
              if (!spec.env().isEmpty()) {
                processBuilder.environment().putAll(spec.env());
                log.atDebug()
                    .setMessage("Process environment variables configured: {} variable(s)")
                    .addArgument(spec.env().size())
                    .log();
              }
              processBuilder.redirectErrorStream(!spec.captureStderr());
              return processBuilder.start();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Write stdin, capture stdout/stderr concurrently, then await process exit.
   *
   * <p>Stdin writing and output draining run concurrently to avoid pipe deadlocks with processes
   * that produce output while reading input.
   *
   * @param process the running process
   * @param spec the execution specification
   * @param startNanos the execution start timestamp in nanoseconds
   * @return a Mono emitting the execution result
   */
  private Mono<ProcessExecutionResult> collectResult(
      final Process process, final ProcessExecutionSpec spec, final long startNanos) {
    final Mono<Boolean> stdinWriter =
        Mono.fromRunnable(() -> writeStdin(process.getOutputStream(), spec.stdin()))
            .subscribeOn(Schedulers.boundedElastic())
            .thenReturn(Boolean.TRUE);
    final Mono<OutputCapture> stdoutCapture =
        Mono.fromCallable(
                () ->
                    readOutput(
                        process.getInputStream(), spec.maxOutputLines(), spec.maxOutputBytes()))
            .subscribeOn(Schedulers.boundedElastic());
    final Mono<OutputCapture> stderrCapture =
        spec.captureStderr()
            ? Mono.fromCallable(
                    () ->
                        readOutput(
                            process.getErrorStream(), spec.maxOutputLines(), spec.maxOutputBytes()))
                .subscribeOn(Schedulers.boundedElastic())
            : Mono.just(OutputCapture.EMPTY);

    return Mono.zip(stdoutCapture, stderrCapture, stdinWriter)
        .flatMap(
            outputs ->
                Mono.fromFuture(process.onExit())
                    .map(
                        exited ->
                            buildResult(exited, outputs.getT1(), outputs.getT2(), startNanos)));
  }

  /**
   * Build the result for a process that exited within the timeout.
   *
   * @param exited the exited process
   * @param stdout the captured standard output
   * @param stderr the captured standard error
   * @param startNanos the execution start timestamp in nanoseconds
   * @return the execution result
   */
  private ProcessExecutionResult buildResult(
      final Process exited,
      final OutputCapture stdout,
      final OutputCapture stderr,
      final long startNanos) {
    final int exitCode = exited.exitValue();
    log.atDebug().setMessage("Process completed with exit code {}").addArgument(exitCode).log();
    return ProcessExecutionResult.of(
        exitCode,
        stdout.lines(),
        stderr.lines(),
        elapsedMillis(startNanos),
        false,
        stdout.truncated() || stderr.truncated());
  }

  /**
   * Build the result for a process that exceeded its timeout and was destroyed.
   *
   * @param spec the execution specification
   * @param actualCommand the command after optional shell wrapping
   * @param startNanos the execution start timestamp in nanoseconds
   * @return a Mono emitting the timed-out result
   */
  private Mono<ProcessExecutionResult> timedOutResult(
      final ProcessExecutionSpec spec, final List<String> actualCommand, final long startNanos) {
    log.atWarn()
        .setMessage("Process timed out after {}s and was destroyed: {}")
        .addArgument(spec.timeoutSeconds())
        .addArgument(actualCommand)
        .log();
    return Mono.just(
        ProcessExecutionResult.of(
            ProcessExecutionResult.TIMEOUT_EXIT_CODE,
            List.of(),
            List.of(),
            elapsedMillis(startNanos),
            true,
            false));
  }

  /**
   * Map a completed execution result to the legacy line-stream contract used by {@link
   * #executeStream(List, String, long, Map, boolean)}.
   *
   * @param result the execution result
   * @param spec the execution specification
   * @return the output lines, or an error for a timed-out or failed process
   */
  private Flux<String> toLineStream(
      final ProcessExecutionResult result, final ProcessExecutionSpec spec) {
    if (result.timedOut()) {
      log.atError()
          .setMessage("Process timed out after {}s: {}")
          .addArgument(spec.timeoutSeconds())
          .addArgument(spec.command())
          .log();
      return Flux.error(
          new WorkflowExecutionException(
              "Process timed out after " + spec.timeoutSeconds() + "s",
              new TimeoutException("Process timed out after " + spec.timeoutSeconds() + "s")));
    }
    if (result.exitCode() != 0) {
      final String output = result.stdout();
      log.atError()
          .setMessage(
              """
              Process failed with exit code {}: {}
              --- Process Output ---
              {}
              --- End Output ---\
              """)
          .addArgument(result.exitCode())
          .addArgument(spec.command())
          .addArgument(output)
          .log();
      return Flux.error(
          new WorkflowExecutionException(
              "Process failed with exit code "
                  + result.exitCode()
                  + "\n--- Output ---\n"
                  + output));
    }
    return Flux.fromIterable(result.stdoutLines());
  }

  /**
   * Read a process output stream to exhaustion, retaining at most the configured caps.
   *
   * <p>Output beyond the caps is drained but discarded so the process is never blocked on a full
   * pipe. Package-private for direct testing.
   *
   * @param stream the process output stream to read
   * @param maxLines maximum lines to retain (non-positive for unlimited)
   * @param maxBytes maximum UTF-8 bytes to retain (non-positive for unlimited)
   * @return the captured output with a truncation flag
   */
  @SuppressWarnings("PMD.UnnecessaryModifier")
  /* package */ OutputCapture readOutput(
      final InputStream stream, final int maxLines, final long maxBytes) {
    final List<String> lines = new ArrayList<>();
    boolean truncated = false;
    long bytes = 0;
    try (final BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line = reader.readLine();
      while (line != null) {
        if (!truncated) {
          // +1 accounts for the newline separator stripped by readLine()
          final long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
          final boolean withinLineCap = maxLines <= 0 || lines.size() < maxLines;
          final boolean withinByteCap = maxBytes <= 0 || bytes + lineBytes <= maxBytes;
          if (withinLineCap && withinByteCap) {
            lines.add(line);
            bytes += lineBytes;
          } else {
            truncated = true;
          }
        }
        line = reader.readLine();
      }
    } catch (final IOException e) {
      log.atWarn()
          .setMessage("Failed to read process output; returning partial capture")
          .setCause(e)
          .log();
    }
    return new OutputCapture(lines, truncated);
  }

  /**
   * Write the given text to the process standard input and close the stream.
   *
   * <p>The stream is always closed, even when there is nothing to write, so processes reading stdin
   * terminate deterministically instead of waiting for input until the timeout. Package-private for
   * direct testing.
   *
   * @param stream the process standard input stream
   * @param stdin the text to write, or null/empty for none
   */
  /* package */ void writeStdin(final OutputStream stream, final String stdin) {
    try (stream) {
      if (stdin != null && !stdin.isEmpty()) {
        stream.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
    } catch (final IOException e) {
      log.atDebug()
          .setMessage("Failed to write stdin to process (it may have exited early)")
          .setCause(e)
          .log();
    }
  }

  /**
   * Compute elapsed wall-clock milliseconds since the given start timestamp.
   *
   * @param startNanos the start timestamp from {@link System#nanoTime()}
   * @return the elapsed milliseconds
   */
  private long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
  }

  /**
   * Wrap the command in a shell based on the OS. Command arguments are properly escaped to prevent
   * shell injection.
   *
   * @param command the original command and arguments
   * @return the shell-wrapped command
   */
  private List<String> wrapInShell(final List<String> command) {
    return wrapInShell(command, System.getProperty("os.name"));
  }

  /**
   * Wrap the command in a shell for the given OS name. Package-private to allow direct testing of
   * both the Windows and non-Windows branches regardless of the OS running the build.
   *
   * @param command the original command and arguments
   * @param osName the {@code os.name} system property value to branch on
   * @return the shell-wrapped command
   */
  /* package */ List<String> wrapInShell(final List<String> command, final String osName) {
    final List<String> wrapped = new ArrayList<>();
    if (osName.toLowerCase(Locale.ROOT).contains("win")) {
      wrapped.add("cmd.exe");
      wrapped.add("/c");
    } else {
      wrapped.add("/bin/sh");
      wrapped.add("-c");
    }
    // Escape shell metacharacters to prevent injection
    final String joined =
        command.stream().map(this::escapeShellArg).collect(Collectors.joining(" "));
    wrapped.add(joined);
    return wrapped;
  }

  /**
   * Escape a shell argument by wrapping in single quotes and escaping single quotes within, but
   * only if necessary. This prevents shell metacharacter injection by handling dangerous characters
   * like semicolons, pipes, redirects, etc.
   *
   * @param arg the argument to escape
   * @return the escaped argument safe for shell execution
   */
  private String escapeShellArg(final String arg) {
    // Check if arg contains dangerous shell metacharacters that require quoting
    // Allow alphanumeric, spaces, common punctuation, paths, hyphens, underscores
    // Only quote if it contains shell special chars like ;|&$`<>(){}[]'"\
    if (arg.matches("^[a-zA-Z0-9._/ :,()\\-]*$")) {
      // Safe argument - contains only benign characters
      return arg;
    }
    // Unsafe argument - wrap in single quotes and escape any single quotes within
    // by closing the quote, adding an escaped quote, and reopening
    return "'" + arg.replace("'", "'\"'\"'") + "'";
  }

  /**
   * Terminate a process if it is still alive, escalating from graceful to forcible destruction.
   *
   * <p>The process is first asked to terminate via {@link Process#destroy()}; if it is still alive
   * after {@link #FORCE_DESTROY_GRACE}, it is killed via {@link Process#destroyForcibly()}.
   * Package-private for direct testing.
   *
   * @param process the process to terminate
   * @return a Mono completing when termination has been initiated or the process already exited
   */
  /* package */ Mono<Void> cleanupProcess(final Process process) {
    return Mono.defer(
            () -> {
              if (!process.isAlive()) {
                return Mono.empty();
              }
              process.destroy();
              return Mono.fromFuture(process.onExit())
                  .timeout(FORCE_DESTROY_GRACE)
                  .then()
                  .onErrorResume(
                      TimeoutException.class,
                      _ ->
                          Mono.fromRunnable(
                              () -> {
                                log.atWarn()
                                    .setMessage(
                                        "Process ignored graceful termination; destroying forcibly")
                                    .log();
                                process.destroyForcibly();
                              }));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Captured output of a single process stream.
   *
   * @param lines the retained output lines
   * @param truncated whether output beyond the configured caps was discarded
   */
  /* package */ record OutputCapture(List<String> lines, boolean truncated) {

    /** Shared empty capture used when a stream is not captured separately. */
    /* package */ static final OutputCapture EMPTY = new OutputCapture(List.of(), false);

    /** Defensively copies the line list. */
    /* package */ OutputCapture {
      lines = List.copyOf(lines);
    }
  }
}
