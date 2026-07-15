// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.List;
import java.util.Map;

/**
 * Immutable specification describing how an external process should be executed.
 *
 * <p>Instances are created via the generated {@link #builder()}. Unset numeric fields are
 * normalized in the compact constructor: a non-positive {@code timeoutSeconds} falls back to {@link
 * #DEFAULT_TIMEOUT_SECONDS}, and non-positive output caps mean {@link #UNLIMITED}.
 *
 * <p>Record components:
 *
 * <ul>
 *   <li><code>command</code> — the command and its arguments to execute (never null after
 *       construction)
 *   <li><code>workingDir</code> — the working directory, or null/blank for the current directory
 *   <li><code>timeoutSeconds</code> — the maximum wall-clock execution time in seconds
 *   <li><code>env</code> — additional environment variables for the process (never null after
 *       construction)
 *   <li><code>useShell</code> — whether to wrap the command in an OS shell (sh/cmd.exe)
 *   <li><code>stdin</code> — text to write to the process standard input, or null for none; the
 *       input stream is always closed after writing so commands reading stdin terminate
 *       deterministically
 *   <li><code>captureStderr</code> — whether to capture stderr separately; when false, stderr is
 *       merged into stdout
 *   <li><code>maxOutputLines</code> — maximum number of output lines to retain per stream ({@link
 *       #UNLIMITED} for no cap); excess output is drained but discarded
 *   <li><code>maxOutputBytes</code> — maximum number of output bytes to retain per stream ({@link
 *       #UNLIMITED} for no cap); excess output is drained but discarded
 * </ul>
 */
public record ProcessExecutionSpec(
    List<String> command,
    String workingDir,
    long timeoutSeconds,
    Map<String, String> env,
    boolean useShell,
    String stdin,
    boolean captureStderr,
    int maxOutputLines,
    long maxOutputBytes) {

  /** Timeout applied when no positive timeout is specified. */
  public static final long DEFAULT_TIMEOUT_SECONDS = 300L;

  /** Cap value indicating that output should not be limited. */
  public static final int UNLIMITED = 0;

  /**
   * Creates a new builder for constructing a {@link ProcessExecutionSpec}.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  private static List<String> copyCommand(final List<String> command) {
    return command == null ? List.of() : List.copyOf(command);
  }

  private static Map<String, String> copyEnv(final Map<String, String> env) {
    return env == null ? Map.of() : Map.copyOf(env);
  }

  /** Normalizes nulls and non-positive numeric fields to their documented defaults. */
  public ProcessExecutionSpec {
    command = copyCommand(command);
    env = copyEnv(env);
    if (timeoutSeconds <= 0) {
      timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }
    maxOutputLines = Math.max(UNLIMITED, maxOutputLines);
    maxOutputBytes = Math.max(UNLIMITED, maxOutputBytes);
  }

  /** Builder for {@link ProcessExecutionSpec}. */
  public static final class Builder {
    /** The command and its arguments to execute. */
    private List<String> commandValue = List.of();

    /** The working directory, or null for the current directory. */
    private String workingDirValue;

    /** The maximum wall-clock execution time in seconds. */
    private long timeoutSecondsValue;

    /** Additional environment variables for the process. */
    private Map<String, String> envValue = Map.of();

    /** Whether to wrap the command in an OS shell. */
    private boolean useShellValue;

    /** Text to write to the process standard input, or null for none. */
    private String stdinValue;

    /** Whether to capture stderr separately from stdout. */
    private boolean captureStderrValue;

    /** Maximum number of output lines to retain per stream. */
    private int maxOutputLinesValue;

    /** Maximum number of output bytes to retain per stream. */
    private long maxOutputBytesValue;

    private Builder() {}

    /**
     * Sets the command and its arguments to execute.
     *
     * @param command the command and arguments
     * @return this builder
     */
    public Builder command(final List<String> command) {
      this.commandValue = copyCommand(command);
      return this;
    }

    /**
     * Sets the working directory for the process.
     *
     * @param workingDir the working directory, or null/blank for the current directory
     * @return this builder
     */
    public Builder workingDir(final String workingDir) {
      this.workingDirValue = workingDir;
      return this;
    }

    /**
     * Sets the maximum wall-clock execution time.
     *
     * @param timeoutSeconds the timeout in seconds
     * @return this builder
     */
    public Builder timeoutSeconds(final long timeoutSeconds) {
      this.timeoutSecondsValue = timeoutSeconds;
      return this;
    }

    /**
     * Sets additional environment variables for the process.
     *
     * @param env the environment variables
     * @return this builder
     */
    public Builder env(final Map<String, String> env) {
      this.envValue = copyEnv(env);
      return this;
    }

    /**
     * Sets whether to wrap the command in an OS shell.
     *
     * @param useShell true to execute via an OS shell
     * @return this builder
     */
    public Builder useShell(final boolean useShell) {
      this.useShellValue = useShell;
      return this;
    }

    /**
     * Sets the text to write to the process standard input.
     *
     * @param stdin the standard input text, or null for none
     * @return this builder
     */
    public Builder stdin(final String stdin) {
      this.stdinValue = stdin;
      return this;
    }

    /**
     * Sets whether to capture stderr separately from stdout.
     *
     * @param captureStderr true to capture stderr separately
     * @return this builder
     */
    public Builder captureStderr(final boolean captureStderr) {
      this.captureStderrValue = captureStderr;
      return this;
    }

    /**
     * Sets the maximum number of output lines to retain per stream.
     *
     * @param maxOutputLines the line cap, or {@link #UNLIMITED} for no cap
     * @return this builder
     */
    public Builder maxOutputLines(final int maxOutputLines) {
      this.maxOutputLinesValue = maxOutputLines;
      return this;
    }

    /**
     * Sets the maximum number of output bytes to retain per stream.
     *
     * @param maxOutputBytes the byte cap, or {@link #UNLIMITED} for no cap
     * @return this builder
     */
    public Builder maxOutputBytes(final long maxOutputBytes) {
      this.maxOutputBytesValue = maxOutputBytes;
      return this;
    }

    /**
     * Builds the {@link ProcessExecutionSpec} from the configured values.
     *
     * @return the constructed spec
     */
    public ProcessExecutionSpec build() {
      return new ProcessExecutionSpec(
          commandValue,
          workingDirValue,
          timeoutSecondsValue,
          envValue,
          useShellValue,
          stdinValue,
          captureStderrValue,
          maxOutputLinesValue,
          maxOutputBytesValue);
    }
  }
}
