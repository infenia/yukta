// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the structured reports a process wrote (JUnit XML, Checkstyle XML, SARIF) into a bounded,
 * normalized list of failures, so a caller does not have to recover them from text by regex.
 *
 * <p>Only files modified during the run count, so a report left by an earlier run is not read. A
 * report that cannot be read is skipped: the text output stays the fallback.
 */
@Slf4j
@UtilityClass
@SuppressWarnings({
  "PMD.AvoidCatchingGenericException",
  "PMD.AvoidDuplicateLiterals",
  "PMD.OnlyOneReturn",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
/* package */ class ReportFailures {

  /** Failures kept in the result; the rest is only counted. */
  /* package */ static final int MAX_FAILURES = 50;

  /** Longest message kept per failure. */
  private static final int MAX_MESSAGE = 300;

  /** Shared JSON mapper for SARIF. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The formats a report can have. */
  /* package */ static final List<String> FORMATS = List.of("junit", "checkstyle", "sarif");

  /**
   * One report to read.
   *
   * @param format {@code junit}, {@code checkstyle} or {@code sarif}
   * @param glob a glob relative to the working directory, such as {@code
   *     build/test-results/**}{@code /TEST-*.xml}
   */
  /* package */ record Spec(String format, String glob) {}

  /**
   * Validates one {@code reports} entry.
   *
   * @param entry the configured map
   * @return the spec
   * @throws IllegalArgumentException if the format is unknown or the glob is missing
   */
  /* package */ static Spec spec(final Object entry) {
    if (!(entry instanceof final Map<?, ?> map)) {
      throw new IllegalArgumentException("reports entries must be {format, glob}");
    }
    final String format = String.valueOf(map.get("format")).trim().toLowerCase(Locale.ROOT);
    if (!FORMATS.contains(format)) {
      throw new IllegalArgumentException(
          "reports format must be one of " + FORMATS + ", got: " + map.get("format"));
    }
    final Object glob = map.get("glob");
    if (glob == null || glob.toString().isBlank()) {
      throw new IllegalArgumentException("reports glob is required");
    }
    return new Spec(format, glob.toString().trim());
  }

  /**
   * Reads the failures of every report written since {@code since}.
   *
   * @param specs the reports to read
   * @param baseDir the directory the globs are relative to
   * @param since only files modified at or after this instant are read
   * @return the failures (at most {@link #MAX_FAILURES}) and their total number
   */
  /* package */ static Map<String, Object> read(
      final List<Spec> specs, final Path baseDir, final Instant since) {
    final List<Map<String, Object>> failures = new ArrayList<>();
    int total = 0;
    for (final Spec spec : specs) {
      for (final Path file : matching(spec, baseDir, since)) {
        final List<Map<String, Object>> found = parse(spec.format(), file);
        total += found.size();
        for (final Map<String, Object> failure : found) {
          if (failures.size() < MAX_FAILURES) {
            failures.add(failure);
          }
        }
      }
    }
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("failures", failures);
    result.put("totalFailures", total);
    return result;
  }

  private static List<Path> matching(final Spec spec, final Path baseDir, final Instant since) {
    final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + spec.glob());
    try (Stream<Path> walk = Files.walk(baseDir)) {
      return walk.filter(Files::isRegularFile)
          .filter(file -> matcher.matches(baseDir.relativize(file)))
          .filter(file -> modifiedSince(file, since))
          .sorted()
          .toList();
    } catch (IOException | RuntimeException e) {
      log.atWarn()
          .setMessage("Cannot list reports for {}")
          .addArgument(spec.glob())
          .setCause(e)
          .log();
      return List.of();
    }
  }

  private static boolean modifiedSince(final Path file, final Instant since) {
    return !Instant.ofEpochMilli(file.toFile().lastModified()).isBefore(since);
  }

  private static List<Map<String, Object>> parse(final String format, final Path file) {
    try {
      return switch (format) {
        case "junit" -> junit(xml(file));
        case "checkstyle" -> checkstyle(xml(file));
        default -> sarif(JSON.readTree(Files.readString(file)));
      };
    } catch (IOException | SAXException | ParserConfigurationException | RuntimeException e) {
      log.atWarn().setMessage("Cannot read report {}").addArgument(file).setCause(e).log();
      return List.of();
    }
  }

  private static Document xml(final Path file)
      throws IOException, SAXException, ParserConfigurationException {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder().parse(file.toFile());
  }

  private static List<Map<String, Object>> junit(final Document doc) {
    final List<Map<String, Object>> out = new ArrayList<>();
    final NodeList cases = doc.getElementsByTagName("testcase");
    for (int i = 0; i < cases.getLength(); i++) {
      final Element testcase = (Element) cases.item(i);
      final Element problem = firstChild(testcase, "failure", "error");
      if (problem != null) {
        final String test =
            testcase.getAttribute("classname") + "." + testcase.getAttribute("name");
        out.add(
            failure(
                "test",
                testcase.getAttribute("classname"),
                null,
                problem.getAttribute("type"),
                firstLine(problem.getAttribute("message"), problem.getTextContent()),
                test));
      }
    }
    return out;
  }

  private static List<Map<String, Object>> checkstyle(final Document doc) {
    final List<Map<String, Object>> out = new ArrayList<>();
    final NodeList files = doc.getElementsByTagName("file");
    for (int i = 0; i < files.getLength(); i++) {
      final Element file = (Element) files.item(i);
      final NodeList errors = file.getElementsByTagName("error");
      for (int j = 0; j < errors.getLength(); j++) {
        final Element error = (Element) errors.item(j);
        out.add(
            failure(
                "style",
                file.getAttribute("name"),
                number(error.getAttribute("line")),
                error.getAttribute("source"),
                error.getAttribute("message"),
                null));
      }
    }
    return out;
  }

  private static List<Map<String, Object>> sarif(final JsonNode root) {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (final JsonNode run : root.path("runs")) {
      for (final JsonNode result : run.path("results")) {
        final String level = result.path("level").asString("warning");
        if (!"error".equals(level) && !"warning".equals(level)) {
          continue;
        }
        final JsonNode location = result.path("locations").path(0).path("physicalLocation");
        out.add(
            failure(
                "analysis",
                location.path("artifactLocation").path("uri").asString(""),
                location.path("region").path("startLine").isNumber()
                    ? location.path("region").path("startLine").asInt()
                    : null,
                result.path("ruleId").asString(""),
                result.path("message").path("text").asString(""),
                null));
      }
    }
    return out;
  }

  private static Element firstChild(final Element parent, final String... names) {
    for (final String name : names) {
      final NodeList nodes = parent.getElementsByTagName(name);
      if (nodes.getLength() > 0) {
        return (Element) nodes.item(0);
      }
    }
    return null;
  }

  private static Integer number(final String value) {
    try {
      return value.isBlank() ? null : Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String firstLine(final String message, final String text) {
    final String source = message.isBlank() ? text : message;
    final String line = source.strip().lines().findFirst().orElse("");
    return line.length() > MAX_MESSAGE ? line.substring(0, MAX_MESSAGE) : line;
  }

  private static Map<String, Object> failure(
      final String kind,
      final String file,
      final Integer line,
      final String rule,
      final String message,
      final String test) {
    final Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("kind", kind);
    failure.put("file", file);
    failure.put("line", line);
    failure.put("rule", rule);
    failure.put("message", firstLine(message, ""));
    if (test != null) {
      failure.put("test", test);
    }
    return failure;
  }
}
