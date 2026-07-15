// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.List;
import java.util.Map;

/**
 * Fully resolved and validated configuration of a single process executor invocation.
 *
 * <p>Built by the plugin after variable resolution; execution fields map onto {@link
 * ProcessExecutionSpec} while the remaining fields control how the result is turned into an output
 * message.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>command: resolved command and arguments
 *   <li>workingDir: resolved working directory, or null for the current directory
 *   <li>timeout: resolved timeout in seconds (positive)
 *   <li>env: resolved environment variables
 *   <li>useShell: whether to execute the command via an OS shell
 *   <li>outputFormat: shape of the output message payload
 *   <li>failureMode: how to react to a non-zero exit code or timeout
 *   <li>inputMode: how the input message is handed to the process
 *   <li>routeByExitCode: whether output messages are stamped with the "success"/"failure" source
 *       port based on the process outcome (requires failureMode CONTINUE)
 *   <li>includeOutput: whether structured payloads embed stdout/stderr text
 *   <li>includeInput: whether structured payloads embed the original input payload
 *   <li>captureStderr: whether stderr is captured separately instead of merged into stdout
 *   <li>maxOutputLines: maximum output lines retained per stream (0 = unlimited)
 *   <li>maxOutputBytes: maximum output bytes retained per stream (0 = unlimited)
 * </ul>
 */
/* package */ record ProcessExecutorConfig(
    List<String> command,
    String workingDir,
    long timeout,
    Map<String, String> env,
    boolean useShell,
    OutputFormat outputFormat,
    FailureMode failureMode,
    InputMode inputMode,
    boolean routeByExitCode,
    boolean includeOutput,
    boolean includeInput,
    boolean captureStderr,
    int maxOutputLines,
    long maxOutputBytes) {

  private static List<String> copyCommand(final List<String> command) {
    return command == null ? List.of() : List.copyOf(command);
  }

  private static Map<String, String> copyEnv(final Map<String, String> env) {
    return env == null ? Map.of() : Map.copyOf(env);
  }

  /** Defensively copies mutable collections to prevent external mutations. */
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  public ProcessExecutorConfig {
    command = copyCommand(command);
    env = copyEnv(env);
  }

  /**
   * Creates a new builder for constructing a {@link ProcessExecutorConfig}.
   *
   * @return a new builder instance
   */
  /* package */ static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ProcessExecutorConfig}. */
  @SuppressWarnings("PMD.TooManyMethods")
  /* package */ static final class Builder {
    /** The resolved command and arguments. */
    private List<String> commandValue = List.of();

    /** The resolved working directory, or null for the current directory. */
    private String workingDirValue;

    /** The resolved timeout in seconds. */
    private long timeoutValue;

    /** The resolved environment variables. */
    private Map<String, String> envValue = Map.of();

    /** Whether to execute the command via an OS shell. */
    private boolean useShellValue;

    /** Shape of the output message payload. */
    private OutputFormat outputFormatValue;

    /** How to react to a non-zero exit code or timeout. */
    private FailureMode failureModeValue;

    /** How the input message is handed to the process. */
    private InputMode inputModeValue;

    /** Whether output messages are stamped with the success/failure source port. */
    private boolean routeByExitCodeValue;

    /** Whether structured payloads embed stdout/stderr text. */
    private boolean includeOutputValue;

    /** Whether structured payloads embed the original input payload. */
    private boolean includeInputValue;

    /** Whether stderr is captured separately instead of merged into stdout. */
    private boolean captureStderrValue;

    /** Maximum output lines retained per stream. */
    private int maxOutputLinesValue;

    /** Maximum output bytes retained per stream. */
    private long maxOutputBytesValue;

    /**
     * Sets the command and its arguments.
     *
     * @param command the command and arguments
     * @return this builder
     */
    /* package */ Builder command(final List<String> command) {
      this.commandValue = copyCommand(command);
      return this;
    }

    /**
     * Sets the working directory.
     *
     * @param workingDir the working directory, or null for the current directory
     * @return this builder
     */
    /* package */ Builder workingDir(final String workingDir) {
      this.workingDirValue = workingDir;
      return this;
    }

    /**
     * Sets the timeout in seconds.
     *
     * @param timeout the timeout in seconds
     * @return this builder
     */
    /* package */ Builder timeout(final long timeout) {
      this.timeoutValue = timeout;
      return this;
    }

    /**
     * Sets the environment variables.
     *
     * @param env the environment variables
     * @return this builder
     */
    /* package */ Builder env(final Map<String, String> env) {
      this.envValue = copyEnv(env);
      return this;
    }

    /**
     * Sets whether to execute the command via an OS shell.
     *
     * @param useShell true to execute via an OS shell
     * @return this builder
     */
    /* package */ Builder useShell(final boolean useShell) {
      this.useShellValue = useShell;
      return this;
    }

    /**
     * Sets the shape of the output message payload.
     *
     * @param outputFormat the output format
     * @return this builder
     */
    /* package */ Builder outputFormat(final OutputFormat outputFormat) {
      this.outputFormatValue = outputFormat;
      return this;
    }

    /**
     * Sets how to react to a non-zero exit code or timeout.
     *
     * @param failureMode the failure mode
     * @return this builder
     */
    /* package */ Builder failureMode(final FailureMode failureMode) {
      this.failureModeValue = failureMode;
      return this;
    }

    /**
     * Sets how the input message is handed to the process.
     *
     * @param inputMode the input mode
     * @return this builder
     */
    /* package */ Builder inputMode(final InputMode inputMode) {
      this.inputModeValue = inputMode;
      return this;
    }

    /**
     * Sets whether output messages are stamped with the success/failure source port.
     *
     * @param routeByExitCode true to route by exit code (requires failureMode CONTINUE)
     * @return this builder
     */
    /* package */ Builder routeByExitCode(final boolean routeByExitCode) {
      this.routeByExitCodeValue = routeByExitCode;
      return this;
    }

    /**
     * Sets whether structured payloads embed stdout/stderr text.
     *
     * @param includeOutput true to embed captured output
     * @return this builder
     */
    /* package */ Builder includeOutput(final boolean includeOutput) {
      this.includeOutputValue = includeOutput;
      return this;
    }

    /**
     * Sets whether structured payloads embed the original input payload.
     *
     * @param includeInput true to embed the input payload
     * @return this builder
     */
    /* package */ Builder includeInput(final boolean includeInput) {
      this.includeInputValue = includeInput;
      return this;
    }

    /**
     * Sets whether stderr is captured separately instead of merged into stdout.
     *
     * @param captureStderr true to capture stderr separately
     * @return this builder
     */
    /* package */ Builder captureStderr(final boolean captureStderr) {
      this.captureStderrValue = captureStderr;
      return this;
    }

    /**
     * Sets the maximum output lines retained per stream.
     *
     * @param maxOutputLines the line cap (0 = unlimited)
     * @return this builder
     */
    /* package */ Builder maxOutputLines(final int maxOutputLines) {
      this.maxOutputLinesValue = maxOutputLines;
      return this;
    }

    /**
     * Sets the maximum output bytes retained per stream.
     *
     * @param maxOutputBytes the byte cap (0 = unlimited)
     * @return this builder
     */
    /* package */ Builder maxOutputBytes(final long maxOutputBytes) {
      this.maxOutputBytesValue = maxOutputBytes;
      return this;
    }

    /**
     * Builds the {@link ProcessExecutorConfig} from the configured values.
     *
     * @return the constructed config
     */
    /* package */ ProcessExecutorConfig build() {
      return new ProcessExecutorConfig(
          commandValue,
          workingDirValue,
          timeoutValue,
          envValue,
          useShellValue,
          outputFormatValue,
          failureModeValue,
          inputModeValue,
          routeByExitCodeValue,
          includeOutputValue,
          includeInputValue,
          captureStderrValue,
          maxOutputLinesValue,
          maxOutputBytesValue);
    }
  }
}
