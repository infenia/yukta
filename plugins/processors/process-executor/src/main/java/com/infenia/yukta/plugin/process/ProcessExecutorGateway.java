// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.core.PluginLogSink;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Gateway for executing external processes with reactive streaming output. */
@Slf4j
@Service
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods", "PMD.GodClass"})
@NoArgsConstructor
public class ProcessExecutorGateway {

  /** Executables workflows may run; empty means none unless {@link #allowAny}. */
  private Set<String> allowedCommands = Set.of();

  /** Whether any executable may run while the list is empty. */
  private boolean allowAny;

  /** What a workflow may set for an allowed executable (environment, working directory). */
  private final ProcessLimits limits = new ProcessLimits();

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
    return executeForResult(spec, PluginLogSink.NOOP);
  }

  /**
   * Like {@link #executeForResult(ProcessExecutionSpec)}, and also hands every output line to
   * {@code sink} as the process writes it. The sink sees all lines; the retention caps apply only
   * to the lines kept in the result.
   *
   * @param spec the execution specification
   * @param sink receives each stdout/stderr line live
   * @return a Mono emitting the execution result
   */
  public Mono<ProcessExecutionResult> executeForResult(
      final ProcessExecutionSpec spec, final PluginLogSink sink) {
    if (spec == null) {
      return Mono.error(new IllegalArgumentException("spec is mandatory"));
    }
    if (spec.command().isEmpty()) {
      return Mono.error(new IllegalArgumentException("command must not be empty"));
    }
    final String refusal = refusalFor(spec);
    if (refusal != null) {
      return Mono.error(new IllegalArgumentException(refusal));
    }
    final List<String> actualCommand =
        spec.useShell() ? wrapInShell(spec.command()) : spec.command();

    return Mono.fromSupplier(System::nanoTime)
        .flatMap(startNanos -> runProcess(spec, actualCommand, startNanos, sink));
  }

  /**
   * Lets workflows run any executable while no list is set. Off by default: with neither a list nor
   * this switch, every command is refused.
   *
   * @param allowAny true to run any executable when {@code allowed-commands} is empty
   */
  @Value("${yukta.process.allow-any:false}")
  /* default */ void setAllowAny(final boolean allowAny) {
    this.allowAny = allowAny;
  }

  /**
   * Sets the executables workflows may run. With a list, a shell is refused too, since it could run
   * anything. Without one, nothing runs unless {@code yukta.process.allow-any} is true.
   *
   * @param allowed executable names exactly as they appear in the command
   */
  @Value("${yukta.process.allowed-commands:}")
  /* default */ void setAllowedCommands(final List<String> allowed) {
    this.allowedCommands = allowed == null ? Set.of() : Set.copyOf(allowed);
  }

  /**
   * Environment variable names a workflow may set although they can change what an allowed
   * executable runs (see {@code yukta.process.allowed-commands}); anything else of that kind is
   * refused while the list of commands is set.
   *
   * @param allowed the variable names, exactly as written
   */
  @Value("${yukta.process.allowed-env:}")
  /* default */ void setAllowedEnv(final List<String> allowed) {
    limits.allowEnv(allowed);
  }

  /**
   * Confines the working directory of a process to a directory while the list of commands is set: a
   * relative {@code workingDir} is taken from it, no working directory means it, and one that
   * leaves it (by {@code ..} or a link) is refused.
   *
   * @param root the directory, or empty for no limit
   */
  @Value("${yukta.process.working-dir-root:}")
  /* default */ void setWorkingDirRoot(final String root) {
    limits.confineTo(root);
  }

  private String refusalFor(final ProcessExecutionSpec spec) {
    if (allowedCommands.isEmpty()) {
      return allowAny
          ? null
          : "Process execution is off: set yukta.process.allowed-commands (or"
              + " yukta.process.allow-any=true to run any executable)";
    }
    if (spec.useShell()) {
      return "useShell is not allowed while yukta.process.allowed-commands is set";
    }
    final String executable = spec.command().get(0);
    if (!allowedCommands.contains(executable)) {
      return "Command '" + executable + "' is not in yukta.process.allowed-commands";
    }
    final String env = limits.refusalForEnv(spec.env());
    return env != null ? env : limits.refusalForDirectory(spec.workingDir());
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
      final ProcessExecutionSpec spec,
      final List<String> actualCommand,
      final long startNanos,
      final PluginLogSink sink) {
    return Mono.usingWhen(
            startProcess(spec, actualCommand),
            process -> collectResult(process, spec, startNanos, sink),
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
              final File directory =
                  limits.directory(spec.workingDir(), !allowedCommands.isEmpty());
              if (directory != null) {
                processBuilder.directory(directory);
                log.atDebug()
                    .setMessage("Process working directory set to: {}")
                    .addArgument(directory)
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
      final Process process,
      final ProcessExecutionSpec spec,
      final long startNanos,
      final PluginLogSink sink) {
    final Mono<Boolean> stdinWriter =
        Mono.fromRunnable(() -> writeStdin(process.getOutputStream(), spec.stdin()))
            .subscribeOn(Schedulers.boundedElastic())
            .thenReturn(Boolean.TRUE);
    final Mono<OutputCapture> stdoutCapture =
        Mono.fromCallable(
                () ->
                    readOutput(
                        process.getInputStream(),
                        spec.maxOutputLines(),
                        spec.maxOutputBytes(),
                        line -> sink.line("stdout", line)))
            .subscribeOn(Schedulers.boundedElastic());
    final Mono<OutputCapture> stderrCapture =
        spec.captureStderr()
            ? Mono.fromCallable(
                    () ->
                        readOutput(
                            process.getErrorStream(),
                            spec.maxOutputLines(),
                            spec.maxOutputBytes(),
                            line -> sink.line("stderr", line)))
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
  /* package */ OutputCapture readOutput(
      final InputStream stream, final int maxLines, final long maxBytes) {
    return readOutput(stream, maxLines, maxBytes, line -> {});
  }

  /**
   * Like {@link #readOutput(InputStream, int, long)}, and also passes every line read, retained or
   * not, to {@code onLine} as soon as it is read.
   *
   * @param stream the process output stream to read
   * @param maxLines maximum lines to retain (non-positive for unlimited)
   * @param maxBytes maximum UTF-8 bytes to retain (non-positive for unlimited)
   * @param onLine receives each line live; a failure in it is logged and does not stop the read
   * @return the captured output with a truncation flag
   */
  @SuppressWarnings("PMD.UnnecessaryModifier")
  /* package */ OutputCapture readOutput(
      final InputStream stream,
      final int maxLines,
      final long maxBytes,
      final Consumer<String> onLine) {
    final List<String> lines = new ArrayList<>();
    boolean truncated = false;
    long bytes = 0;
    try (final BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line = reader.readLine();
      while (line != null) {
        publish(onLine, line);
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

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void publish(final Consumer<String> onLine, final String line) {
    try {
      onLine.accept(line);
    } catch (final RuntimeException e) {
      log.atDebug().setMessage("Live log sink rejected a line").setCause(e).log();
    }
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
   * Wrap the command in a shell based on the OS. The elements are run as one script; see {@link
   * #wrapInShell(List, String)}.
   *
   * @param command the script, as elements joined with spaces
   * @return the shell-wrapped command
   */
  private List<String> wrapInShell(final List<String> command) {
    return wrapInShell(command, System.getProperty("os.name"));
  }

  /**
   * Wrap the command in a shell for the given OS name. The elements are joined with spaces into one
   * script that the shell runs as written, so pipes, {@code &&} and redirects work. A value that
   * comes from outside must be made one inert word with {@link #quoteForShell} before it gets here.
   * Package-private to allow direct testing of both the Windows and non-Windows branches regardless
   * of the OS running the build.
   *
   * @param command the script, as elements joined with spaces
   * @param osName the {@code os.name} system property value to branch on
   * @return the shell-wrapped command
   */
  /* package */ List<String> wrapInShell(final List<String> command, final String osName) {
    final List<String> wrapped = new ArrayList<>();
    if (isWindows(osName)) {
      wrapped.add("cmd.exe");
      wrapped.add("/c");
    } else {
      wrapped.add("/bin/sh");
      wrapped.add("-c");
    }
    wrapped.add(String.join(" ", command));
    return wrapped;
  }

  private static boolean isWindows(final String osName) {
    return osName.toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * Make a value one inert word of a shell script, whatever characters it holds.
   *
   * <p>On Unix the value goes in single quotes, with each single quote written as {@code '"'"'}. On
   * Windows it goes in double quotes, where {@code cmd.exe} keeps {@code & | < > ^ ( )} literal and
   * trailing backslashes are doubled so they cannot escape the closing quote. {@code cmd.exe} has
   * no way to protect a double quote, a percent sign (variable expansion) or a line break, so such
   * a value is refused: pass it with {@code inputMode} ENV or STDIN instead.
   *
   * @param value the value to quote
   * @param osName the {@code os.name} system property value to branch on
   * @return the quoted word
   * @throws IllegalArgumentException if the value cannot be quoted for {@code cmd.exe}
   */
  /* package */ static String quoteForShell(final String value, final String osName) {
    if (!isWindows(osName)) {
      return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    if (value.chars().anyMatch(c -> c == '"' || c == '%' || c == '\n' || c == '\r' || c == 0)) {
      throw new IllegalArgumentException(
          "A value used in a shell command on Windows cannot contain a double quote, a percent sign"
              + " or a line break; pass it with inputMode ENV or STDIN instead");
    }
    int trailing = 0;
    while (trailing < value.length() && value.charAt(value.length() - 1 - trailing) == '\\') {
      trailing++;
    }
    return "\"" + value + "\\".repeat(trailing) + "\"";
  }

  /**
   * Terminate a process and everything it started, escalating from graceful to forcible
   * destruction.
   *
   * <p>The descendants are listed first, because once the parent is gone they are re-parented and
   * can no longer be found. The process and its descendants are asked to terminate via {@link
   * Process#destroy()}; any of them still alive after {@link #FORCE_DESTROY_GRACE} is killed via
   * {@link ProcessHandle#destroyForcibly()}. Killing the descendants too closes the pipes they
   * share with the parent, so the output readers are not left blocked. Package-private for direct
   * testing.
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
              final List<ProcessHandle> tree =
                  Stream.concat(Stream.of(process.toHandle()), process.descendants()).toList();
              tree.forEach(ProcessHandle::destroy);
              return Mono.fromFuture(
                      CompletableFuture.allOf(
                          tree.stream()
                              .map(ProcessHandle::onExit)
                              .toArray(CompletableFuture[]::new)))
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
                                tree.stream()
                                    .filter(ProcessHandle::isAlive)
                                    .forEach(ProcessHandle::destroyForcibly);
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
