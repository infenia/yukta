// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.List;

/**
 * Immutable outcome of an external process execution.
 *
 * <p>Unlike exception-based APIs, this result treats the exit code as data: a non-zero exit code is
 * a regular result, not an error. Callers decide how to react to failures.
 *
 * <p>Record components:
 *
 * <ul>
 *   <li><code>exitCode</code> — the process exit code, or {@link #TIMEOUT_EXIT_CODE} if the process
 *       timed out
 *   <li><code>stdoutLines</code> — the captured standard output lines (never null after
 *       construction); contains merged stderr output when the execution did not capture stderr
 *       separately
 *   <li><code>stderrLines</code> — the captured standard error lines (never null after
 *       construction); empty when stderr was merged into stdout
 *   <li><code>durationMillis</code> — the wall-clock execution duration in milliseconds
 *   <li><code>timedOut</code> — whether the process was terminated because it exceeded the timeout;
 *       captured output is not available for timed-out executions
 *   <li><code>outputTruncated</code> — whether any captured output was truncated due to configured
 *       caps
 * </ul>
 */
public record ProcessExecutionResult(
    int exitCode,
    List<String> stdoutLines,
    List<String> stderrLines,
    long durationMillis,
    boolean timedOut,
    boolean outputTruncated) {

  /** Synthetic exit code reported when the process was terminated due to timeout. */
  public static final int TIMEOUT_EXIT_CODE = -1;

  /**
   * Factory method for creating immutable process execution results.
   *
   * <p>All list inputs are normalized and defensively copied by the canonical constructor.
   */
  @SuppressWarnings("PMD.ShortMethodName")
  public static ProcessExecutionResult of(
      final int exitCode,
      final List<String> stdoutLines,
      final List<String> stderrLines,
      final long durationMillis,
      final boolean timedOut,
      final boolean outputTruncated) {
    return new ProcessExecutionResult(
        exitCode, stdoutLines, stderrLines, durationMillis, timedOut, outputTruncated);
  }

  /** Normalizes null line lists to empty immutable lists. */
  public ProcessExecutionResult {
    stdoutLines = stdoutLines == null ? List.of() : List.copyOf(stdoutLines);
    stderrLines = stderrLines == null ? List.of() : List.copyOf(stderrLines);
  }

  /**
   * Whether the execution completed within the timeout and exited with code zero.
   *
   * @return true if the process succeeded
   */
  public boolean isSuccess() {
    return !timedOut && exitCode == 0;
  }

  /**
   * The captured standard output as a single string with newline separators.
   *
   * @return the joined stdout text, empty if no output was captured
   */
  public String stdout() {
    return String.join("\n", stdoutLines);
  }

  /**
   * The captured standard error as a single string with newline separators.
   *
   * @return the joined stderr text, empty if stderr was merged into stdout or no output captured
   */
  public String stderr() {
    return String.join("\n", stderrLines);
  }
}
