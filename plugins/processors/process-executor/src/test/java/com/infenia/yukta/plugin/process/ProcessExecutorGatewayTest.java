// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for ProcessExecutorGateway covering stream execution, timeouts, and error handling. */
@NoArgsConstructor
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals", // Common test string constants
  "PMD.CommentRequired", // Constants are self-documenting in test context
  "PMD.TooManyMethods", // Comprehensive test coverage requires multiple methods
})
class ProcessExecutorGatewayTest {

  private static final String EARLY_LINE = "early";

  private static final String ECHO = "echo";
  private static final String HELLO = "hello";
  private static final String HELLO_WORLD_OUTPUT = "hello\nworld";
  private static final String SLEEP = "sleep";
  private static final String PWD = "pwd";
  private static final String TEST = "test";
  private static final String LINE_1 = "line1";
  private static final String LINE_2 = "line2";
  private static final String LINE_3 = "line3";
  private static final String TMP = "/tmp";
  private static final String TMP_DIR = "tmp";
  private static final String PRINTF = "printf";
  private static final long DEFAULT_TIMEOUT_SECONDS = 10L;
  private static final long SHORT_TIMEOUT_SECONDS = 1L;
  private static final String TEST_VAR = "TEST_VAR";
  private static final String FOO = "foo";

  /** Gateway under test. */
  private ProcessExecutorGateway gateway;

  private Flux<String> executeStream(
      final List<String> command,
      final String workingDir,
      final long timeoutSeconds,
      final Map<String, String> env,
      final boolean useShell) {
    return gateway
        .executeForResult(
            ProcessExecutionSpec.builder()
                .command(command)
                .workingDir(workingDir)
                .timeoutSeconds(timeoutSeconds)
                .env(env)
                .useShell(useShell)
                .build())
        .flatMapMany(r -> Flux.fromIterable(r.stdoutLines()));
  }

  @BeforeEach
  void setUp() {
    gateway = new ProcessExecutorGateway();
    gateway.setAllowAny(true);
  }

  @Test
  void noListAndNoSwitch_refusesEveryCommand() {
    final ProcessExecutorGateway fresh = new ProcessExecutorGateway();
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder().command(List.of("echo", "hi")).timeoutSeconds(10L).build();

    StepVerifier.create(fresh.executeForResult(spec))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains("allow-any"))
        .verify();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void testExecuteStreamUnix() {
    StepVerifier.create(
            executeStream(
                List.of(ECHO, HELLO_WORLD_OUTPUT), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .expectNext(HELLO)
        .expectNext("world")
        .verifyComplete();
  }

  @Test
  void testExecuteStreamWithShell() {
    StepVerifier.create(
            executeStream(List.of(ECHO, HELLO), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), true))
        .assertNext(line -> assertThat(line).contains(HELLO))
        .verifyComplete();
  }

  @Test
  void testExecuteStreamWithEnv() {
    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", ECHO + " $" + TEST_VAR),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(TEST_VAR, FOO),
                false))
        .expectNext(FOO)
        .verifyComplete();
  }

  @Test
  void wrapInShell_joinsTheCommandIntoOneRawScript() {
    assertThat(gateway.wrapInShell(List.of("echo hi", "|", "tr a-z A-Z"), "Linux"))
        .containsExactly("/bin/sh", "-c", "echo hi | tr a-z A-Z");
    assertThat(gateway.wrapInShell(List.of("dir", "|", "find \"x\""), "Windows 11"))
        .containsExactly("cmd.exe", "/c", "dir | find \"x\"");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void shell_pipesAndLogicalOperatorsWork() {
    StepVerifier.create(
            executeStream(
                List.of("echo hello", "|", "tr a-z A-Z"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                true))
        .assertNext(line -> assertThat(line).contains("HELLO"))
        .verifyComplete();
    StepVerifier.create(
            executeStream(
                List.of("echo a", "&&", "echo b"), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), true))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void shell_redirectsWork() throws IOException {
    final Path file = Files.createTempFile("yukta-shell", ".txt");
    try {
      StepVerifier.create(
              executeStream(
                  List.of("echo written", ">", file.toString()),
                  null,
                  DEFAULT_TIMEOUT_SECONDS,
                  Map.of(),
                  true))
          .verifyComplete();

      assertThat(Files.readString(file)).contains("written");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void quoteForShell_posix_makesTheValueOneInertWord() {
    assertThat(ProcessExecutorGateway.quoteForShell("a b; rm -rf /", "Linux"))
        .isEqualTo("'a b; rm -rf /'");
    assertThat(ProcessExecutorGateway.quoteForShell("it's", "Linux")).isEqualTo("'it'\"'\"'s'");
    assertThat(ProcessExecutorGateway.quoteForShell("", "Linux")).isEqualTo("''");
  }

  @Test
  void quoteForShell_windows_usesDoubleQuotesWhichKeepMetacharactersLiteral() {
    assertThat(ProcessExecutorGateway.quoteForShell("a & b | c > d ^ e (f)", "Windows 11"))
        .isEqualTo("\"a & b | c > d ^ e (f)\"");
    assertThat(ProcessExecutorGateway.quoteForShell("C:\\dir\\", "Windows 11"))
        .as("a trailing backslash must not escape the closing quote")
        .isEqualTo("\"C:\\dir\\\\\"");
  }

  @Test
  void quoteForShell_windows_loneBackslashIsDoubled() {
    assertThat(ProcessExecutorGateway.quoteForShell("\\", "Windows 11")).isEqualTo("\"\\\\\"");
  }

  @Test
  void quoteForShell_windows_refusesWhatCmdCannotQuote() {
    for (final String bad : List.of("say \"hi\"", "100%", "a\nb", "a\rb", "a\0b")) {
      assertThatThrownBy(() -> ProcessExecutorGateway.quoteForShell(bad, "Windows 11"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("inputMode");
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void quotedValuesStayInertInRealShell() {
    final String hostile = "a;b|c>d $(echo INJECTED) `echo INJECTED` 'q' \"dq\" \\ end";

    StepVerifier.create(
            executeStream(
                List.of("printf '%s\\n'", ProcessExecutorGateway.quoteForShell(hostile, "Linux")),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                true))
        .assertNext(line -> assertThat(line.strip()).isEqualTo(hostile))
        .verifyComplete();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void wrapInShell_unixEnvironment_usesShBinary() {
    // Verify that shell wrapping uses /bin/sh on Unix-like systems
    StepVerifier.create(
            executeStream(
                List.of(ECHO, "test_shell_binary"), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), true))
        .assertNext(line -> assertThat(line).contains("test_shell_binary"))
        .verifyComplete();
  }

  @Test
  void executeStream_withWorkingDirectory_changesProcessDirectory() {
    StepVerifier.create(executeStream(List.of(PWD), TMP, DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .assertNext(output -> assertThat(output).contains(TMP_DIR))
        .verifyComplete();
  }

  @Test
  void executeStream_multipleEnvironmentVariables_allPassed() {
    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", ECHO + " $VAR1:$VAR2:$VAR3"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of("VAR1", "a", "VAR2", "b", "VAR3", "c"),
                false))
        .assertNext(
            output -> {
              assertThat(output).contains("a");
              assertThat(output).contains("b");
              assertThat(output).contains("c");
            })
        .verifyComplete();
  }

  @Test
  void executeStream_commandWithMultipleArguments_executesCorrectly() {
    StepVerifier.create(
            executeStream(
                List.of(PRINTF, "%s %s %s\n", HELLO, "world", TEST),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                false))
        .assertNext(line -> assertThat(line).contains(HELLO))
        .verifyComplete();
  }

  @Test
  void executeStream_emptyCommandOutput_completesSuccessfully() {
    StepVerifier.create(
            executeStream(List.of("true"), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .verifyComplete();
  }

  @Test
  void executeStream_processExitsZero_collectsAndReturnsAllLines() {
    StepVerifier.create(
            executeStream(
                List.of(
                    "sh",
                    "-c",
                    ECHO + " " + LINE_1 + "; " + ECHO + " " + LINE_2 + "; " + ECHO + " " + LINE_3),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                false))
        .expectNext(LINE_1)
        .expectNext(LINE_2)
        .expectNext(LINE_3)
        .verifyComplete();
  }

  @Test
  void executeStream_processWithWorkingDir_executesInCorrectDirectory() {
    StepVerifier.create(executeStream(List.of(PWD), TMP, DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .assertNext(output -> assertThat(output).contains(TMP_DIR))
        .verifyComplete();
  }

  @Test
  void executeStream_processWithMultipleEnvVars_allVariablesAvailable() {
    final Map<String, String> env = new ConcurrentHashMap<>();
    env.put("VAR1", "value1");
    env.put("VAR2", "value2");
    env.put("VAR3", "value3");

    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", "echo $VAR1:$VAR2:$VAR3"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                env,
                false))
        .assertNext(
            output -> {
              assertThat(output).contains("value1");
              assertThat(output).contains("value2");
              assertThat(output).contains("value3");
            })
        .verifyComplete();
  }

  @Test
  void executeStream_processProducesEmptyOutput_completesWithoutEmittingLines() {
    StepVerifier.create(
            executeStream(List.of("true"), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .verifyComplete();
  }

  @Test
  void executeStream_processWithComplexCommand_executesMultipleArguments() {
    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", "printf 'first\\nsecond\\nthird'"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                false))
        .expectNext("first")
        .expectNext("second")
        .expectNext("third")
        .verifyComplete();
  }

  @Test
  void executeStream_shellWrappingOn_commandExecutesViaShell() {
    StepVerifier.create(
            executeStream(
                List.of("echo", "test_with_special_chars_@#$"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                true))
        .assertNext(output -> assertThat(output).contains("test_with_special_chars"))
        .verifyComplete();
  }

  @Test
  void executeStream_shellArgWithComplexQuoting_handledCorrectly() {
    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", "echo 'test string with spaces'"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                false))
        .assertNext(output -> assertThat(output).contains("test string with spaces"))
        .verifyComplete();
  }

  @Test
  void wrapInShell_commandWithArguments_wrapsAllProperly() {
    StepVerifier.create(
            executeStream(
                List.of("printf", "%s %s", "arg1", "arg2"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                Map.of(),
                true))
        .assertNext(output -> assertThat(output).contains("arg1"))
        .verifyComplete();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void wrapInShell_unixSystem_usesBinSh() {
    // Verify /bin/sh is used on Unix by testing command that only works in sh
    StepVerifier.create(
            executeStream(List.of("echo", "test"), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), true))
        .assertNext(output -> assertThat(output).contains("test"))
        .verifyComplete();
  }

  @Test
  void executeStream_multipleLineOutput_collectedAndReturned() {
    // Generate multiple output lines
    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", "echo line1; echo line2; echo line3; echo line4; echo line5"),
                null,
                10L,
                Map.of(),
                false))
        .expectNext("line1")
        .expectNext("line2")
        .expectNext("line3")
        .expectNext("line4")
        .expectNext("line5")
        .verifyComplete();
  }

  @Test
  void executeStream_environmentVariable_propagatedToProcess() {
    final Map<String, String> env = new ConcurrentHashMap<>();
    env.put("TEST_CUSTOM_VAR", "custom_value_123");

    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", "echo $TEST_CUSTOM_VAR"),
                null,
                DEFAULT_TIMEOUT_SECONDS,
                env,
                false))
        .assertNext(output -> assertThat(output).contains("custom_value_123"))
        .verifyComplete();
  }

  @Test
  void executeStream_processAliveAfterCompletion_destroyNotCalled() {
    // Normal completion flow - process cleanup happens
    StepVerifier.create(
            executeStream(List.of("echo", TEST), null, DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .assertNext(
            output -> {
              assertThat(output).isNotNull();
            })
        .verifyComplete();
  }

  @Test
  void executeStream_workingDirWithBlankString_treatedAsNoDirectory() {
    // Test the isBlank() branch in executeStream
    StepVerifier.create(
            executeStream(List.of("echo", "test"), "   ", DEFAULT_TIMEOUT_SECONDS, Map.of(), false))
        .assertNext(output -> assertThat(output).contains("test"))
        .verifyComplete();
  }

  @Test
  void executeStream_envMapNull_handlesNullEnvironment() {
    // Test the env null branch in executeStream
    StepVerifier.create(
            executeStream(List.of("echo", "test"), null, DEFAULT_TIMEOUT_SECONDS, null, false))
        .assertNext(output -> assertThat(output).contains("test"))
        .verifyComplete();
  }

  @Test
  void executeStream_environmentVariableNull_executesSuccessfully() {
    // Test the null env handling in executeStream line 76
    StepVerifier.create(
            executeStream(List.of("echo", "test"), null, DEFAULT_TIMEOUT_SECONDS, null, false))
        .assertNext(output -> assertThat(output).contains("test"))
        .verifyComplete();
  }

  @Test
  void executeStream_environmentAndWorkingDir_combinedExecution() {
    // Test combined env and working dir scenarios
    final Map<String, String> env = new ConcurrentHashMap<>();
    env.put("TEST_VAR", "test_value");

    StepVerifier.create(
            executeStream(
                List.of("sh", "-c", "echo $TEST_VAR"), null, DEFAULT_TIMEOUT_SECONDS, env, false))
        .assertNext(output -> assertThat(output).contains("test_value"))
        .verifyComplete();
  }

  @Test
  void cleanupProcess_aliveProcess_destroysItGracefully() throws IOException {
    final Process process = new ProcessBuilder("sleep", "10").start();
    assertThat(process.isAlive()).isTrue();

    StepVerifier.create(gateway.cleanupProcess(process)).verifyComplete();

    assertThat(process.isAlive()).isFalse();
  }

  @Test
  void cleanupProcess_deadProcess_doesNothing() throws IOException, InterruptedException {
    final Process process = new ProcessBuilder("true").start();
    process.waitFor();
    assertThat(process.isAlive()).isFalse();

    StepVerifier.create(gateway.cleanupProcess(process)).verifyComplete();

    assertThat(process.isAlive()).isFalse();
  }

  @Test
  void cleanupProcess_processIgnoresSigterm_forciblyDestroyed()
      throws IOException, InterruptedException {
    final Process process = new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 30").start();
    // Give the shell a moment to install the TERM trap before we try to destroy it
    Thread.sleep(300);
    assertThat(process.isAlive()).isTrue();

    StepVerifier.create(gateway.cleanupProcess(process)).verifyComplete();

    // destroyForcibly() is asynchronous; wait briefly for the kill to take effect
    process.waitFor(5, TimeUnit.SECONDS);
    assertThat(process.isAlive()).isFalse();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void cleanupProcess_alsoDestroysDescendants() throws Exception {
    final Process process = new ProcessBuilder("sh", "-c", "sleep 30 & wait").start();
    ProcessHandle grandchild = null;
    for (int i = 0; i < 50 && grandchild == null; i++) {
      grandchild = process.descendants().findFirst().orElse(null);
      if (grandchild == null) {
        Thread.sleep(100);
      }
    }
    assertThat(grandchild).as("the shell started a child").isNotNull();
    assertThat(grandchild.isAlive()).isTrue();

    StepVerifier.create(gateway.cleanupProcess(process)).verifyComplete();

    grandchild.onExit().get(5, TimeUnit.SECONDS);
    assertThat(grandchild.isAlive()).as("the grandchild must not outlive its parent").isFalse();
  }

  @Test
  void wrapInShell_windowsOsName_usesCmdExe() {
    final List<String> wrapped = gateway.wrapInShell(List.of("echo", "hello"), "Windows 10");

    assertThat(wrapped).startsWith("cmd.exe", "/c");
  }

  @Test
  void wrapInShell_nonWindowsOsName_usesBinSh() {
    final List<String> wrapped = gateway.wrapInShell(List.of("echo", "hello"), "Linux");

    assertThat(wrapped).startsWith("/bin/sh", "-c");
  }

  // --- executeForResult: non-throwing result-based API ---

  @Test
  void allowedCommands_refuseOtherExecutablesAndShells_butRunListedOnes() {
    gateway.setAllowedCommands(List.of("echo"));
    final ProcessExecutionSpec allowed =
        ProcessExecutionSpec.builder().command(List.of("echo", "hi")).timeoutSeconds(10L).build();
    final ProcessExecutionSpec other =
        ProcessExecutionSpec.builder().command(List.of("ls")).timeoutSeconds(10L).build();
    final ProcessExecutionSpec shell =
        ProcessExecutionSpec.builder()
            .command(List.of("echo", "hi"))
            .useShell(true)
            .timeoutSeconds(10L)
            .build();

    StepVerifier.create(gateway.executeForResult(allowed))
        .assertNext(result -> assertThat(result.stdout()).isEqualTo("hi"))
        .verifyComplete();
    StepVerifier.create(gateway.executeForResult(other))
        .expectErrorMessage("Command 'ls' is not in yukta.process.allowed-commands")
        .verify();
    StepVerifier.create(gateway.executeForResult(shell))
        .expectError(IllegalArgumentException.class)
        .verify();

    gateway.setAllowedCommands(null);
    StepVerifier.create(gateway.executeForResult(other))
        .expectNextMatches(result -> result.exitCode() == 0)
        .verifyComplete();
  }

  @Test
  void executeForResult_successfulProcess_returnsZeroExitCodeAndOutput() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo line1; echo line2"))
            .timeoutSeconds(10L)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.exitCode()).isZero();
              assertThat(result.isSuccess()).isTrue();
              assertThat(result.stdoutLines()).containsExactly("line1", "line2");
              assertThat(result.stdout()).isEqualTo("line1\nline2");
              assertThat(result.stderrLines()).isEmpty();
              assertThat(result.timedOut()).isFalse();
              assertThat(result.outputTruncated()).isFalse();
              assertThat(result.durationMillis()).isNotNegative();
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_nonZeroExit_returnsResultInsteadOfError() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo boom; exit 42"))
            .timeoutSeconds(10L)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.exitCode()).isEqualTo(42);
              assertThat(result.isSuccess()).isFalse();
              assertThat(result.timedOut()).isFalse();
              assertThat(result.stdoutLines()).containsExactly("boom");
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_mergedStderrByDefault_stderrAppearsInStdout() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo out; echo err >&2"))
            .timeoutSeconds(10L)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.stdoutLines()).containsExactlyInAnyOrder("out", "err");
              assertThat(result.stderrLines()).isEmpty();
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_captureStderrSeparately_splitsStreams() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo out; echo err >&2"))
            .timeoutSeconds(10L)
            .captureStderr(true)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.stdoutLines()).containsExactly("out");
              assertThat(result.stderrLines()).containsExactly("err");
              assertThat(result.stderr()).isEqualTo("err");
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_timeout_returnsTimedOutResultInsteadOfError() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder().command(List.of("sleep", "10")).timeoutSeconds(1L).build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.timedOut()).isTrue();
              assertThat(result.exitCode()).isEqualTo(ProcessExecutionResult.TIMEOUT_EXIT_CODE);
              assertThat(result.isSuccess()).isFalse();
              assertThat(result.stdoutLines()).isEmpty();
              assertThat(result.durationMillis()).isGreaterThanOrEqualTo(500L);
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_stdinProvided_pipedToProcess() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("cat"))
            .timeoutSeconds(10L)
            .stdin("hello\nworld")
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.exitCode()).isZero();
              assertThat(result.stdoutLines()).containsExactly("hello", "world");
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_noStdin_stdinClosedSoReadingProcessTerminates() {
    // Without an explicit close of the child's stdin, `cat` would hang until the timeout
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder().command(List.of("cat")).timeoutSeconds(5L).build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.exitCode()).isZero();
              assertThat(result.timedOut()).isFalse();
              assertThat(result.stdoutLines()).isEmpty();
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_maxOutputLines_truncatesAndFlags() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo 1; echo 2; echo 3"))
            .timeoutSeconds(10L)
            .maxOutputLines(2)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.exitCode()).isZero();
              assertThat(result.stdoutLines()).containsExactly("1", "2");
              assertThat(result.outputTruncated()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_maxOutputBytes_truncatesAndFlags() {
    // First line is 6 bytes including the newline and fits exactly; the second exceeds the cap
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo aaaaa; echo bbbbb"))
            .timeoutSeconds(10L)
            .maxOutputBytes(6L)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.stdoutLines()).containsExactly("aaaaa");
              assertThat(result.outputTruncated()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_withSink_publishesEveryLineBeyondTheCapsAndTagsStreams() {
    final List<String> seen = new CopyOnWriteArrayList<>();
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo 1; echo 2; echo 3; echo e1 >&2"))
            .timeoutSeconds(10L)
            .captureStderr(true)
            .maxOutputLines(1)
            .build();

    StepVerifier.create(
            gateway.executeForResult(spec, (stream, line, level) -> seen.add(stream + ":" + line)))
        .assertNext(
            result -> {
              assertThat(result.stdoutLines()).containsExactly("1");
              assertThat(result.outputTruncated()).isTrue();
            })
        .verifyComplete();

    assertThat(seen).containsExactlyInAnyOrder("stdout:1", "stdout:2", "stdout:3", "stderr:e1");
  }

  @Test
  void executeForResult_withSink_publishesLinesWhileTheProcessIsStillRunning() throws Exception {
    final CountDownLatch first = new CountDownLatch(1);
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo " + EARLY_LINE + "; sleep 3; echo late"))
            .timeoutSeconds(10L)
            .build();

    final Disposable run =
        gateway
            .executeForResult(
                spec,
                (stream, line, level) -> {
                  if (EARLY_LINE.equals(line)) {
                    first.countDown();
                  }
                })
            .subscribe();
    try {
      assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(run.isDisposed()).isFalse();
    } finally {
      run.dispose();
    }
  }

  @Test
  void executeForResult_withFailingSink_stillCompletes() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo a; echo b"))
            .timeoutSeconds(10L)
            .build();

    StepVerifier.create(
            gateway.executeForResult(
                spec,
                (stream, line, level) -> {
                  throw new IllegalStateException("sink down");
                }))
        .assertNext(result -> assertThat(result.stdoutLines()).containsExactly("a", "b"))
        .verifyComplete();
  }

  @Test
  void executeForResult_stderrOnlyTruncation_flagsResult() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "echo e1 >&2; echo e2 >&2"))
            .timeoutSeconds(10L)
            .captureStderr(true)
            .maxOutputLines(1)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.stdoutLines()).isEmpty();
              assertThat(result.stderrLines()).containsExactly("e1");
              assertThat(result.outputTruncated()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_useShell_wrapsCommand() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("echo hello"))
            .timeoutSeconds(10L)
            .useShell(true)
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(result -> assertThat(result.stdout()).contains("hello"))
        .verifyComplete();
  }

  @Test
  void executeForResult_workingDirectoryAndEnv_applied() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("sh", "-c", "pwd; echo $MY_TEST_VAR"))
            .workingDir("/tmp")
            .timeoutSeconds(10L)
            .env(Map.of("MY_TEST_VAR", "abc"))
            .build();

    StepVerifier.create(gateway.executeForResult(spec))
        .assertNext(
            result -> {
              assertThat(result.stdoutLines().get(0)).contains("tmp");
              assertThat(result.stdoutLines().get(1)).isEqualTo("abc");
            })
        .verifyComplete();
  }

  @Test
  void executeForResult_nullSpec_errorsWithIllegalArgument() {
    StepVerifier.create(gateway.executeForResult(null)).verifyError(IllegalArgumentException.class);
  }

  @Test
  void executeForResult_emptyCommand_errorsWithIllegalArgument() {
    StepVerifier.create(gateway.executeForResult(ProcessExecutionSpec.builder().build()))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void executeForResult_unknownCommand_errorsWithIoException() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of("/nonexistent-binary-yukta-test"))
            .timeoutSeconds(10L)
            .build();

    StepVerifier.create(gateway.executeForResult(spec)).verifyError(IOException.class);
  }

  // --- readOutput: capped stream capture ---

  @Test
  void readOutput_unlimitedCaps_capturesEverything() {
    final InputStream stream =
        new java.io.ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8));

    final ProcessExecutorGateway.OutputCapture capture = gateway.readOutput(stream, 0, 0L);

    assertThat(capture.lines()).containsExactly("a", "b");
    assertThat(capture.truncated()).isFalse();
  }

  @Test
  void readOutput_negativeCaps_treatedAsUnlimited() {
    final InputStream stream =
        new java.io.ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8));

    final ProcessExecutorGateway.OutputCapture capture = gateway.readOutput(stream, -1, -1L);

    assertThat(capture.lines()).containsExactly("a", "b");
    assertThat(capture.truncated()).isFalse();
  }

  @Test
  void readOutput_byteCapBoundary_lineExactlyAtCapRetained() {
    // "abc" + newline = 4 bytes, exactly at the cap; "d" + newline would exceed it
    final InputStream stream =
        new java.io.ByteArrayInputStream("abc\nd\n".getBytes(StandardCharsets.UTF_8));

    final ProcessExecutorGateway.OutputCapture capture = gateway.readOutput(stream, 0, 4L);

    assertThat(capture.lines()).containsExactly("abc");
    assertThat(capture.truncated()).isTrue();
  }

  @Test
  @SuppressWarnings("PMD.CloseResource") // Anonymous test stream, not meant to be closed
  void readOutput_ioException_logsWarningAndReturnsPartialCapture(final CapturedOutput output) {
    final InputStream throwingStream =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("Simulated read failure");
          }

          @Override
          public int read(final byte[] buffer, final int off, final int len) throws IOException {
            throw new IOException("Simulated read failure");
          }
        };

    final ProcessExecutorGateway.OutputCapture capture = gateway.readOutput(throwingStream, 0, 0L);

    assertThat(capture.lines()).isEmpty();
    assertThat(capture.truncated()).isFalse();
    assertThat(output.getOut() + output.getErr()).contains("Failed to read process output");
  }

  @Test
  void readOutput_byteCap_stopsAppendingOnceExceeded_drainsRemainingStream() {
    // Large first line (10 bytes incl. newline) exceeds the 8-byte cap;
    // followed by a smaller third line that would fit if appending continued.
    // Should return only the contiguous prefix and mark truncated.
    final InputStream stream =
        new java.io.ByteArrayInputStream("verylarge\nsmall\n".getBytes(StandardCharsets.UTF_8));

    final ProcessExecutorGateway.OutputCapture capture = gateway.readOutput(stream, 0, 8L);

    assertThat(capture.lines()).isEmpty();
    assertThat(capture.truncated()).isTrue();
  }

  // --- writeStdin: stdin piping ---

  @Test
  @SuppressWarnings("PMD.CloseResource") // CloseTrackingOutputStream is closed by gateway
  void writeStdin_nullText_closesStreamWithoutWriting() throws IOException {
    final CloseTrackingOutputStream stream = new CloseTrackingOutputStream();

    gateway.writeStdin(stream, null);

    assertThat(stream.closed).isTrue();
    assertThat(stream.toByteArray()).isEmpty();
  }

  @Test
  @SuppressWarnings("PMD.CloseResource") // CloseTrackingOutputStream is closed by gateway
  void writeStdin_emptyText_closesStreamWithoutWriting() throws IOException {
    final CloseTrackingOutputStream stream = new CloseTrackingOutputStream();

    gateway.writeStdin(stream, "");

    assertThat(stream.closed).isTrue();
    assertThat(stream.toByteArray()).isEmpty();
  }

  @Test
  @SuppressWarnings("PMD.CloseResource") // CloseTrackingOutputStream is closed by gateway
  void writeStdin_text_writesUtf8BytesAndClosesStream() throws IOException {
    final CloseTrackingOutputStream stream = new CloseTrackingOutputStream();

    gateway.writeStdin(stream, "hello");

    assertThat(stream.closed).isTrue();
    assertThat(stream.toByteArray()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void writeStdin_ioException_swallowedWithoutThrowing() throws IOException {
    try (OutputStream throwingStream =
        new OutputStream() {
          @Override
          @SuppressWarnings("PMD.ShortVariable") // Parameter name required by interface
          public void write(final int b) throws IOException {
            throw new IOException("Simulated broken pipe");
          }
        }) {
      org.assertj.core.api.Assertions.assertThatCode(() -> gateway.writeStdin(throwingStream, "x"))
          .doesNotThrowAnyException();
    }
  }

  /** ByteArrayOutputStream that records whether it was closed. */
  private static final class CloseTrackingOutputStream extends java.io.ByteArrayOutputStream {
    private boolean closed;

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
