// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a workflow may set for an allowed executable while {@code yukta.process.allowed-commands} is
 * set: the environment variables that change what the executable runs are refused, and the working
 * directory can be confined to one directory. Both are a second line behind the list of commands:
 * with {@code LD_PRELOAD}, {@code PATH}, {@code GIT_SSH_COMMAND} or {@code JAVA_TOOL_OPTIONS} an
 * allowed {@code git} or {@code java} runs any code. A deny-list cannot be complete, so this is not
 * isolation.
 */
@SuppressWarnings("PMD.AtLeastOneConstructor")
/* default */ final class ProcessLimits {

  /**
   * Names that change what an executable runs: the loader and interpreter option variables, the
   * path and the home directory (tools read their configuration from it).
   */
  private static final Pattern RISKY_ENV =
      Pattern.compile(
          "(?i)PATH|HOME|IFS|ENV|BASH_ENV|BASHOPTS|SHELLOPTS|PS4|PROMPT_COMMAND|ZDOTDIR|CLASSPATH"
              + "|LESSOPEN|LESSCLOSE|LD_.*|DYLD_.*|GIT_.*|XDG_.*|NODE_.*|PYTHON.*|PERL.*|RUBY.*"
              + "|.*_OPTIONS|.*_OPTS");

  /** Risky names the operator allows anyway. */
  private Set<String> allowedEnv = Set.of();

  /** The directory every working directory must lie in, if any. */
  private Optional<Path> root = Optional.empty();

  /**
   * Allow risky environment variable names.
   *
   * @param allowed the names, exactly as written; null or empty for none
   */
  /* default */ void allowEnv(final List<String> allowed) {
    this.allowedEnv = allowed == null ? Set.of() : Set.copyOf(allowed);
  }

  /**
   * Confine the working directory.
   *
   * @param directory the directory, or null or blank for no limit
   */
  /* default */ void confineTo(final String directory) {
    this.root =
        directory == null || directory.isBlank()
            ? Optional.empty()
            : Optional.of(Path.of(directory));
  }

  /**
   * Check the environment of a process.
   *
   * @param env the variables the workflow sets
   * @return why it is refused, or null
   */
  /* default */ String refusalForEnv(final Map<String, String> env) {
    return env.keySet().stream()
        .filter(name -> RISKY_ENV.matcher(name).matches() && !allowedEnv.contains(name))
        .findFirst()
        .map(
            name ->
                "Environment variable '"
                    + name
                    + "' is not allowed while yukta.process.allowed-commands is set"
                    + " (list it in yukta.process.allowed-env to allow it)")
        .orElse(null);
  }

  /**
   * Check the working directory of a process.
   *
   * @param workingDir the directory the workflow names, or null
   * @return why it is refused, or null
   */
  /* default */ String refusalForDirectory(final String workingDir) {
    String refusal = null;
    if (root.isPresent() && workingDir != null && !workingDir.isBlank()) {
      try {
        final Path base = root.get().toRealPath();
        final Path dir = base.resolve(workingDir).normalize();
        final Path real = Files.exists(dir) ? dir.toRealPath() : dir;
        if (!real.startsWith(base)) {
          refusal =
              "Working directory '" + workingDir + "' is outside yukta.process.working-dir-root";
        }
      } catch (IOException | InvalidPathException e) {
        refusal = "Working directory '" + workingDir + "' cannot be checked: " + e.getMessage();
      }
    }
    return refusal;
  }

  /**
   * The directory to run in.
   *
   * @param workingDir the directory the workflow names, or null
   * @param confined true while the limits apply (the list of commands is set)
   * @return the directory; relative to the root when there is one, the root itself if none was
   *     named; null for the server's own
   */
  /* default */ File directory(final String workingDir, final boolean confined) {
    final boolean none = workingDir == null || workingDir.isBlank();
    File directory = none ? null : new File(workingDir);
    if (confined && root.isPresent()) {
      directory = none ? root.get().toFile() : root.get().resolve(workingDir).toFile();
    }
    return directory;
  }
}
