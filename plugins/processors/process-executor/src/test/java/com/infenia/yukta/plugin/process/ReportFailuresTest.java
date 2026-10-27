// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for ReportFailures on small hand-written reports. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class ReportFailuresTest {

  /** Before every file written by a test. */
  private static final Instant LONG_AGO = Instant.EPOCH;

  /** Where the reports are written. */
  @TempDir /* default */ Path dir;

  private Path write(final String name, final String text) throws IOException {
    final Path file = dir.resolve(name);
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
    return Files.writeString(file, text);
  }

  private Map<String, Object> read(final String format, final String glob) {
    return ReportFailures.read(List.of(new ReportFailures.Spec(format, glob)), dir, LONG_AGO);
  }

  @Test
  void junit_failureAndError_areListedWithTestName() throws IOException {
    write(
        "build/TEST-a.xml",
        """
        <testsuite><testcase classname="p.FooTest" name="ok"/>
          <testcase classname="p.FooTest" name="bad"><failure type="AssertionError" message="expected 1 but was 2">stack
        more</failure></testcase>
          <testcase classname="p.FooTest" name="boom"><error type="NPE">trace</error></testcase>
        </testsuite>
        """);

    final Map<String, Object> result = read("junit", "build/TEST-*.xml");

    assertThat(result).containsEntry("totalFailures", 2);
    assertThat((List<?>) result.get("failures"))
        .hasSize(2)
        .first()
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("kind", "test")
        .containsEntry("test", "p.FooTest.bad")
        .containsEntry("rule", "AssertionError")
        .containsEntry("message", "expected 1 but was 2");
  }

  @Test
  void checkstyle_errors_haveFileLineRuleMessage() throws IOException {
    write(
        "build/checkstyle.xml",
        """
        <checkstyle><file name="src/A.java">
          <error line="12" column="3" severity="error" message="Missing Javadoc" source="com.puppycrawl.JavadocMethod"/>
        </file><file name="src/B.java"/></checkstyle>
        """);

    final Map<String, Object> result = read("checkstyle", "build/*.xml");

    assertThat(result).containsEntry("totalFailures", 1);
    assertThat((List<Map<String, Object>>) result.get("failures"))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f).containsEntry("file", "src/A.java").containsEntry("line", 12);
              assertThat(f).containsEntry("message", "Missing Javadoc");
            });
  }

  @Test
  void sarif_errorsAndWarnings_arePicked_notesAreNot() throws IOException {
    write(
        "r.sarif",
        """
        {"runs":[{"results":[
          {"ruleId":"R1","level":"error","message":{"text":"bad"},
           "locations":[{"physicalLocation":{"artifactLocation":{"uri":"x.go"},"region":{"startLine":7}}}]},
          {"ruleId":"R2","level":"note","message":{"text":"fyi"}}]}]}
        """);

    final Map<String, Object> result = read("sarif", "*.sarif");

    assertThat(result).containsEntry("totalFailures", 1);
    assertThat((List<Map<String, Object>>) result.get("failures"))
        .singleElement()
        .satisfies(f -> assertThat(f).containsEntry("file", "x.go").containsEntry("line", 7));
  }

  @Test
  void greenRun_hasNoFailures() throws IOException {
    write("build/TEST-a.xml", "<testsuite><testcase classname=\"p.T\" name=\"ok\"/></testsuite>");

    assertThat(read("junit", "build/TEST-*.xml"))
        .containsEntry("totalFailures", 0)
        .containsEntry("failures", List.of());
  }

  @Test
  void malformedReport_isSkipped() throws IOException {
    write("build/TEST-a.xml", "<testsuite><testcase");
    write(
        "build/TEST-b.xml",
        "<testsuite><testcase classname=\"p\" name=\"n\"><failure"
            + " message=\"m\"/></testcase></testsuite>");

    assertThat(read("junit", "build/TEST-*.xml")).containsEntry("totalFailures", 1);
  }

  @Test
  void oldReport_isNotRead() throws IOException {
    write(
        "build/TEST-a.xml",
        "<testsuite><testcase classname=\"p\" name=\"n\"><failure"
            + " message=\"m\"/></testcase></testsuite>");

    final Map<String, Object> result =
        ReportFailures.read(
            List.of(new ReportFailures.Spec("junit", "build/TEST-*.xml")),
            dir,
            Instant.now().plusSeconds(60));

    assertThat(result).containsEntry("totalFailures", 0);
  }

  @Test
  void manyFailures_areCappedButCounted() throws IOException {
    final String cases =
        IntStream.range(0, 70)
            .mapToObj(
                i ->
                    "<testcase classname=\"p\" name=\"t"
                        + i
                        + "\"><failure message=\"m\"/></testcase>")
            .collect(Collectors.joining());
    write("build/TEST-a.xml", "<testsuite>" + cases + "</testsuite>");

    final Map<String, Object> result = read("junit", "build/TEST-*.xml");

    assertThat(result).containsEntry("totalFailures", 70);
    assertThat((List<?>) result.get("failures")).hasSize(ReportFailures.MAX_FAILURES);
  }

  @Test
  void doctype_isRefused() throws IOException {
    write(
        "build/TEST-a.xml",
        "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testsuite>&e;</testsuite>");

    assertThat(read("junit", "build/TEST-*.xml")).containsEntry("totalFailures", 0);
  }

  @Test
  void missingDirectory_givesNoFailures() {
    assertThat(
            ReportFailures.read(
                List.of(new ReportFailures.Spec("junit", "*.xml")),
                dir.resolve("absent"),
                LONG_AGO))
        .containsEntry("totalFailures", 0);
  }

  @Test
  void checkstyle_errorWithoutOrWithBadLine_hasNoLine() throws IOException {
    write(
        "c.xml",
        """
        <checkstyle><file name="A.java"><error message="m1" source="r"/>
        <error line="x" message="m2" source="r"/></file></checkstyle>
        """);

    assertThat((List<Map<String, Object>>) read("checkstyle", "c.xml").get("failures"))
        .hasSize(2)
        .allSatisfy(f -> assertThat(f.get("line")).isNull());
  }

  @Test
  void junit_failureWithoutMessage_usesTheFirstLineOfItsText() throws IOException {
    write(
        "build/TEST-a.xml",
        "<testsuite><testcase classname=\"p\" name=\"n\"><failure type=\"E\">first\n"
            + "second</failure></testcase></testsuite>");

    assertThat((List<Map<String, Object>>) read("junit", "build/TEST-*.xml").get("failures"))
        .singleElement()
        .satisfies(f -> assertThat(f).containsEntry("message", "first"));
  }

  @Test
  void sarif_resultWithoutLocation_hasNoLine() throws IOException {
    write(
        "r.sarif", "{\"runs\":[{\"results\":[{\"ruleId\":\"R\",\"message\":{\"text\":\"m\"}}]}]}");

    assertThat((List<Map<String, Object>>) read("sarif", "r.sarif").get("failures"))
        .singleElement()
        .satisfies(f -> assertThat(f.get("line")).isNull());
  }

  @Test
  void longMessage_isCut() throws IOException {
    write(
        "r.sarif",
        "{\"runs\":[{\"results\":[{\"ruleId\":\"R\",\"message\":{\"text\":\""
            + "x".repeat(500)
            + "\"}}]}]}");

    assertThat((List<Map<String, Object>>) read("sarif", "r.sarif").get("failures"))
        .singleElement()
        .satisfies(f -> assertThat((String) f.get("message")).hasSize(300));
  }

  @Test
  void spec_rejectsUnknownFormatAndMissingGlob() {
    assertThatThrownBy(() -> ReportFailures.spec(Map.of("format", "tap", "glob", "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReportFailures.spec(Map.of("format", "junit")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReportFailures.spec(Map.of("format", "junit", "glob", " ")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReportFailures.spec("junit"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ReportFailures.spec(Map.of("format", "JUnit", "glob", " a ")))
        .isEqualTo(new ReportFailures.Spec("junit", "a"));
  }
}
