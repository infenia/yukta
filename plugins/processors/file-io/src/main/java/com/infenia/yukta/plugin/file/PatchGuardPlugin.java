// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Judges the working-tree change of a git checkout against rules and emits {@code {files,
 * violations}} on {@code pass} (no violation) or {@code reject}.
 */
@Component
@SuppressWarnings({
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CognitiveComplexity",
  "PMD.CyclomaticComplexity",
  "PMD.UseConcurrentHashMap",
  "PMD.NullAssignment",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.CompareObjectsWithEquals"
})
public final class PatchGuardPlugin extends AbstractFilePlugin {

  /** Output port of a change that breaks no rule. */
  private static final String PASS = "pass";

  /** Output port of a change that breaks a rule. */
  private static final String REJECT = "reject";

  /** Seconds a git call may take. */
  private static final long GIT_TIMEOUT_SECONDS = 30;

  /** A git ref the plugin accepts: no leading dash, so it can never read as an option. */
  private static final Pattern REF = Pattern.compile("[\\w][\\w./~^@{}-]*");

  /** A file that holds tests, by the usual naming of Java, Go, JS/TS and Python. */
  private static final Pattern TEST_FILE =
      Pattern.compile(
          "(^|/)(src/test/|tests?/|__tests__/)|Tests?\\.java$|_test\\.go$|\\.(test|spec)\\.[jt]sx?$"
              + "|(^|/)test_[^/]*\\.py$");

  /** A line that declares one test. */
  private static final Pattern TEST_MARKER =
      Pattern.compile(
          "^\\s*(@Test\\b|@ParameterizedTest\\b|func Test\\w*\\(|(it|test)\\(|def test_\\w*\\()");

  /** Default constructor without metrics. */
  public PatchGuardPlugin() {
    super(PluginMetricsReporter.NOOP);
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public PatchGuardPlugin(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    super(reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP));
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Judges a git working-tree change against rules: protected paths, forbidden patterns,"
            + " test removal.",
        "Configure with:\n"
            + "- dir: the git checkout (required; inside the operator's allowed root, if set).\n"
            + "- base: git ref to compare with (default HEAD); staged, unstaged and new files"
            + " count.\n"
            + "- protectedPaths: globs of files the change must not touch.\n"
            + "- forbidPatterns: regexes that no added line may match.\n"
            + "- noTestRemoval: true to refuse deleted test files and fewer test declarations.\n"
            + "- requireChange: true to refuse an empty change.\n"
            + "- failureMode: ERROR (default) or CONTINUE.\n"
            + "Emits {files, violations: [{rule, file, message}]} on port pass or reject.",
        Optional.of(new UiDesign("orange", "gavel", "Patch Guard", 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required("dir", ConfigField.Type.STRING, "The git checkout to judge"),
        ConfigField.optional("base", ConfigField.Type.STRING, "HEAD", "Git ref to compare with"),
        ConfigField.optional(
            "protectedPaths", ConfigField.Type.LIST, null, "Globs the change must not touch"),
        ConfigField.optional(
            "forbidPatterns", ConfigField.Type.LIST, null, "Regexes no added line may match"),
        ConfigField.optional(
            "noTestRemoval", ConfigField.Type.BOOLEAN, false, "Refuse removed tests"),
        ConfigField.optional(
            "requireChange", ConfigField.Type.BOOLEAN, false, "Refuse an empty change"),
        failureModeField());
  }

  @Override
  public String getType() {
    return "PATCH_GUARD";
  }

  @Override
  public List<String> getOutputPorts() {
    return List.of(PASS, REJECT);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          rules(config);
          FailureMode.from(config.get(FAILURE_MODE));
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final Rules rules = rules(config);
    return perMessage(
        input,
        config,
        "patch_guard",
        message -> {
          final Map<String, Object> verdict = judge(rules);
          final boolean clean = ((List<?>) verdict.get("violations")).isEmpty();
          return message.withPayload(verdict).withSourcePort(clean ? PASS : REJECT);
        });
  }

  /** What the config asks for, validated. */
  private record Rules(
      Path dir,
      String base,
      List<PathMatcher> protectedPaths,
      List<Pattern> forbid,
      boolean noTestRemoval,
      boolean requireChange) {}

  private static Rules rules(final Map<String, Object> config) {
    final Object dir = config.get("dir");
    if (dir == null || String.valueOf(dir).isBlank()) {
      throw new IllegalArgumentException("dir is required");
    }
    final Object base = config.getOrDefault("base", "HEAD");
    if (!REF.matcher(String.valueOf(base)).matches()) {
      throw new IllegalArgumentException("base is not a git ref: " + base);
    }
    return new Rules(
        FileSandbox.baseDir(Map.of("baseDir", String.valueOf(dir))),
        String.valueOf(base),
        strings(config, "protectedPaths").stream()
            .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
            .toList(),
        strings(config, "forbidPatterns").stream().map(Pattern::compile).toList(),
        Boolean.parseBoolean(String.valueOf(config.getOrDefault("noTestRemoval", false))),
        Boolean.parseBoolean(String.valueOf(config.getOrDefault("requireChange", false))));
  }

  private static List<String> strings(final Map<String, Object> config, final String key) {
    final Object value = config.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(key + " must be a list");
    }
    return list.stream().map(String::valueOf).toList();
  }

  /** Per-file counts of test declarations removed and added. */
  private static final class FileChange {
    /** Test declarations the change removed. */
    private int removedTests;

    /** Test declarations the change added. */
    private int addedTests;

    /** Whether the file was deleted outright. */
    private boolean deleted;
  }

  private static Map<String, Object> judge(final Rules rules) throws IOException {
    final Map<String, FileChange> files = new java.util.LinkedHashMap<>();
    final List<Map<String, String>> violations = new ArrayList<>();
    FileChange current = null;
    String name = null;
    for (final String line :
        git(rules, "diff", "--no-color", "--unified=0", rules.base()).lines().toList()) {
      if (line.startsWith("--- ")) {
        name = "--- /dev/null".equals(line) ? null : line.substring(6);
      } else if (line.startsWith("+++ ")) {
        final boolean gone = "+++ /dev/null".equals(line);
        final String file = gone ? name : line.substring(6);
        current = files.computeIfAbsent(file, _ -> new FileChange());
        current.deleted = gone;
      } else if (current != null && line.startsWith("+")) {
        final String added = line.substring(1);
        current.addedTests += TEST_MARKER.matcher(added).find() ? 1 : 0;
        checkForbidden(rules, files, current, added, violations);
      } else if (current != null && line.startsWith("-")) {
        current.removedTests += TEST_MARKER.matcher(line.substring(1)).find() ? 1 : 0;
      }
    }
    // new files are untracked: whole content is added
    for (final String file :
        git(rules, "ls-files", "--others", "--exclude-standard").lines().toList()) {
      final FileChange change = files.computeIfAbsent(file, _ -> new FileChange());
      for (final String added :
          java.nio.file.Files.readAllLines(rules.dir().resolve(file), StandardCharsets.UTF_8)) {
        change.addedTests += TEST_MARKER.matcher(added).find() ? 1 : 0;
        checkForbidden(rules, files, change, added, violations);
      }
    }
    files.forEach((file, change) -> fileRules(rules, file, change, violations));
    if (rules.requireChange() && files.isEmpty()) {
      violations.add(violation("requireChange", "", "the change is empty"));
    }
    return Map.of("files", List.copyOf(files.keySet()), "violations", List.copyOf(violations));
  }

  private static void checkForbidden(
      final Rules rules,
      final Map<String, FileChange> files,
      final FileChange change,
      final String added,
      final List<Map<String, String>> violations) {
    for (final Pattern pattern : rules.forbid()) {
      if (pattern.matcher(added).find()) {
        final String file =
            files.entrySet().stream()
                .filter(entry -> entry.getValue() == change)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");
        violations.add(violation("forbidPatterns", file, "added line matches " + pattern));
      }
    }
  }

  private static void fileRules(
      final Rules rules,
      final String file,
      final FileChange change,
      final List<Map<String, String>> violations) {
    if (rules.protectedPaths().stream().anyMatch(glob -> glob.matches(Path.of(file)))) {
      violations.add(violation("protectedPaths", file, "protected path changed"));
    }
    if (rules.noTestRemoval() && TEST_FILE.matcher(file).find()) {
      if (change.deleted) {
        violations.add(violation("noTestRemoval", file, "test file deleted"));
      } else if (change.removedTests > change.addedTests) {
        violations.add(
            violation(
                "noTestRemoval",
                file,
                (change.removedTests - change.addedTests) + " test(s) removed"));
      }
    }
  }

  private static Map<String, String> violation(
      final String rule, final String file, final String message) {
    return Map.of("rule", rule, "file", file, "message", message);
  }

  private static String git(final Rules rules, final String... args) throws IOException {
    final List<String> command = new ArrayList<>(List.of("git", "-C", rules.dir().toString()));
    command.addAll(List.of(args));
    return finish(new ProcessBuilder(command).redirectErrorStream(false).start(), args[0]);
  }

  /** Waits for a git process and returns its stdout; a non-zero exit or a timeout is an error. */
  /* package */ static String finish(final Process process, final String subcommand)
      throws IOException {
    try {
      final byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("git timed out");
      }
      if (process.exitValue() != 0) {
        throw new IOException(
            "git "
                + subcommand
                + " failed: "
                + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                    .strip());
      }
      return new String(out, StandardCharsets.UTF_8);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }
}
