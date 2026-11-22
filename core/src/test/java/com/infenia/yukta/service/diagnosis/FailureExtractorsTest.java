// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the failure extractors on captured tool output. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class FailureExtractorsTest {

  private static NodeLog log(final String resource) throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            FailureExtractorsTest.class.getResourceAsStream("/diagnosis/" + resource))) {
      return new NodeLog(
          "run", "build", new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
    }
  }

  private static NodeLog lines(final String... lines) {
    return new NodeLog("run", "n", List.of(lines));
  }

  @Test
  void gradle_findsTestsCompileErrorsLintAndTasks() throws IOException {
    final List<Finding> found = new GradleFailureExtractor().extract(log("gradle-failure.log"));

    assertThat(found)
        .extracting(Finding::kind, Finding::name, Finding::location, Finding::message)
        .containsExactly(
            tuple(
                Finding.Kind.LINT,
                "MissingJavadocMethod",
                "/repo/core/src/main/java/com/x/Foo.java:42",
                "Missing a Javadoc comment."),
            tuple(
                Finding.Kind.LINT,
                "UnusedPrivateField",
                "/repo/core/src/main/java/com/x/Bar.java:17",
                "Avoid unused private fields such as 'count'."),
            tuple(
                Finding.Kind.TEST,
                "com.x.FooTest > adds()",
                "FooTest.java:27",
                "org.opentest4j.AssertionFailedError: expected: <2> but was: <3>"),
            tuple(
                Finding.Kind.TEST,
                "com.x.BarTest > divides()",
                null,
                "java.lang.ArithmeticException at BarTest.java:12"),
            tuple(Finding.Kind.TASK, ":core:test", null, "FAILED"),
            tuple(
                Finding.Kind.COMPILE,
                null,
                "/repo/core/src/main/java/com/x/Baz.java:9",
                "cannot find symbol"),
            tuple(
                Finding.Kind.COMPILE,
                null,
                "/repo/plugins/k/src/main/kotlin/K.kt:5",
                "Unresolved reference: foo"),
            tuple(
                Finding.Kind.TASK,
                ":core:test",
                null,
                "There were failing tests. See the report at:"
                    + " file:///repo/core/build/reports/tests/test/index.html"));
    assertThat(found.get(2).line()).isEqualTo(7);
    assertThat(found.get(2).nodeId()).isEqualTo("build");
  }

  @Test
  void gradle_keepsCauseThatIsNotTask() throws IOException {
    final List<Finding> found =
        new GradleFailureExtractor().extract(log("gradle-config-failure.log"));

    assertThat(found)
        .extracting(Finding::kind, Finding::message)
        .containsExactly(
            tuple(
                Finding.Kind.ERROR,
                "Could not resolve all files for configuration ':core:compileClasspath'."
                    + " Could not find com.example:missing:1.0."));
  }

  @Test
  void gradle_handlesCauseAtEndOfOutput() {
    final List<Finding> found =
        new GradleFailureExtractor().extract(lines("* What went wrong:", "", "boom", "", "x"));

    assertThat(found).extracting(Finding::message).containsExactly("boom");
    assertThat(new GradleFailureExtractor().extract(lines("* What went wrong:", "* Try:")))
        .extracting(Finding::message)
        .containsExactly("");
  }

  @Test
  void go_findsTestsPackagesAndCompileErrors() throws IOException {
    final List<Finding> found = new GoFailureExtractor().extract(log("go-failure.log"));

    assertThat(found)
        .extracting(Finding::kind, Finding::name, Finding::location, Finding::message)
        .containsExactly(
            tuple(Finding.Kind.TEST, "TestParse", "parse_test.go:21", "got \"a\", want \"b\""),
            tuple(Finding.Kind.TEST, "TestSilent", null, "FAIL"),
            tuple(Finding.Kind.TASK, "github.com/infenia/yukta/cli/internal/parse", null, "FAIL"),
            tuple(Finding.Kind.COMPILE, null, "./cmd/main.go:14", "undefined: missing"));
  }

  @Test
  void js_findsVitestJestTscAndEslint() throws IOException {
    final List<Finding> found = new JsFailureExtractor().extract(log("js-failure.log"));

    assertThat(found)
        .extracting(Finding::kind, Finding::name, Finding::location, Finding::message)
        .containsExactly(
            tuple(
                Finding.Kind.TEST,
                "adds numbers",
                null,
                "AssertionError: expected 3 to be 2 // Object.is equality"),
            tuple(
                Finding.Kind.TEST,
                "math > adds numbers",
                "src/math.test.ts",
                "AssertionError: expected 3 to be 2 // Object.is equality"),
            tuple(Finding.Kind.TEST, "src/other.test.ts", "src/other.test.ts", "FAIL"),
            tuple(
                Finding.Kind.COMPILE,
                "TS2322",
                "src/app.ts:3",
                "Type 'string' is not assignable to type 'number'."),
            tuple(Finding.Kind.COMPILE, "TS2304", "src/b.ts:7", "Cannot find name 'x'."),
            tuple(
                Finding.Kind.LINT,
                "no-unused-vars",
                "/repo/ui/src/c.tsx:3",
                "'y' is defined but never used"));
  }

  @Test
  void js_ignoresEslintProblemsWithoutFile() {
    assertThat(new JsFailureExtractor().extract(lines("  3:5  error  bad thing  some-rule")))
        .isEmpty();
  }

  @Test
  void gradle_testWithoutReasonAndLongCause() {
    final List<Finding> found =
        new GradleFailureExtractor()
            .extract(
                lines(
                    "com.x.T > m() FAILED",
                    "next test",
                    "* What went wrong:",
                    "one",
                    "two",
                    "three",
                    "four"));

    assertThat(found)
        .extracting(Finding::kind, Finding::message)
        .containsExactly(
            tuple(Finding.Kind.TEST, "FAILED"), tuple(Finding.Kind.ERROR, "one two three"));
  }

  @Test
  void report_readsStructuredLines() {
    final List<Finding> found =
        new ReportLineExtractor()
            .extract(
                lines(
                    "noise",
                    "TestFailure: com.x.FooTest#adds | FooTest.java:27 | expected 2 but was 3",
                    "TestFailure: com.x.BarTest#div |  | boom",
                    "LintFailure: MagicNumber | Foo.java:3 | avoid 42"));

    assertThat(found)
        .extracting(Finding::name, Finding::location, Finding::message, Finding::line)
        .containsExactly(
            tuple("com.x.FooTest#adds", "FooTest.java:27", "expected 2 but was 3", 2L),
            tuple("com.x.BarTest#div", null, "boom", 3L),
            tuple("MagicNumber", "Foo.java:3", "avoid 42", 4L));
    assertThat(found.getLast().kind()).isEqualTo(Finding.Kind.LINT);
  }

  @Test
  void finding_cutsLongMessages() {
    final Finding finding =
        new Finding(Finding.Kind.ERROR, "r", "n", null, null, "x".repeat(500), 1);

    assertThat(finding.message()).hasSize(Finding.MAX_MESSAGE + 1).endsWith("…");
  }

  @Test
  void ahead_stopsAtTheEndOfTheOutput() {
    assertThat(lines("a").ahead(0, 5, java.util.regex.Pattern.compile("a"))).isNull();
  }
}
