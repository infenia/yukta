package com.infenia.jagratha.plugin.processor.checkstyle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import com.infenia.jagratha.plugin.ValidationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Processor that converts Checkstyle XML reports to JSONL format. */
@Slf4j
@RequiredArgsConstructor
public class CheckstyleXmlProcessor implements OutputProcessorPlugin {

  private final ObjectMapper objectMapper;
  private static final Pattern REPORT_PATTERN = Pattern.compile("See the report at: file://(\\S+)");

  @Override
  public String getName() {
    return "checkstyle-xml-to-jsonl";
  }

  @Override
  public ProcessorResult process(final ProcessorInput input) {
    ProcessorResult result;
    try {
      final String reportPathStr = resolveReportPathStr(input);
      if (reportPathStr != null && !reportPathStr.isEmpty()) {
        Path reportPath = Path.of(reportPathStr);
        if (!reportPath.isAbsolute()) {
          reportPath = Path.of(input.projectRoot()).resolve(reportPath);
        }

        if (Files.exists(reportPath)) {
          final List<String> jsonLines = parseXmlReport(reportPath);
          result = saveArtifact(jsonLines, input);
        } else {
          result =
              new ProcessorResult(
                  "FAILURE", "Checkstyle report file does not exist: " + reportPath, null);
        }
      } else {
        result =
            new ProcessorResult(
                "FAILURE", "Checkstyle report path not found in output or config.", null);
      }
    } catch (IOException
        | javax.xml.parsers.ParserConfigurationException
        | org.xml.sax.SAXException e) {
      log.error("Failed to process Checkstyle XML", e);
      result =
          new ProcessorResult(
              "FAILURE", "Error processing Checkstyle XML: " + e.getMessage(), null);
    }
    return result;
  }

  private String resolveReportPathStr(final ProcessorInput input) {
    String reportPathStr = (String) input.config().get("reportPath");
    if (reportPathStr == null || reportPathStr.isEmpty()) {
      final Matcher matcher = REPORT_PATTERN.matcher(input.taskOutput());
      if (matcher.find()) {
        reportPathStr = matcher.group(1);
      }
    }
    return reportPathStr;
  }

  private List<String> parseXmlReport(final Path reportPath)
      throws IOException, javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXException {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Disable external entity processing for security
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    final DocumentBuilder builder = factory.newDocumentBuilder();
    final Document doc = builder.parse(reportPath.toFile());
    doc.getDocumentElement().normalize();

    final NodeList fileNodes = doc.getElementsByTagName("file");
    final List<String> jsonLines = new ArrayList<>();

    for (int i = 0; i < fileNodes.getLength(); i++) {
      final String jsonLine = processFileElement((Element) fileNodes.item(i));
      if (jsonLine != null) {
        jsonLines.add(jsonLine);
      }
    }
    return jsonLines;
  }

  private String processFileElement(final Element fileElement) throws JsonProcessingException {
    final String fileName = fileElement.getAttribute("name");
    final NodeList errorNodes = fileElement.getElementsByTagName("error");

    final List<Map<String, String>> violations = new ArrayList<>();
    for (int j = 0; j < errorNodes.getLength(); j++) {
      violations.add(createViolationMap((Element) errorNodes.item(j)));
    }

    String result = null;
    if (!violations.isEmpty()) {
      final Map<String, Object> fileEntry = Map.of("file", fileName, "violations", violations);
      result = objectMapper.writeValueAsString(fileEntry);
    }
    return result;
  }

  private Map<String, String> createViolationMap(final Element errorElement) {
    return Map.of(
        "line", errorElement.getAttribute("line"),
        "column", errorElement.getAttribute("column"),
        "severity", errorElement.getAttribute("severity"),
        "message", errorElement.getAttribute("message"),
        "source", errorElement.getAttribute("source"));
  }

  private ProcessorResult saveArtifact(final List<String> jsonLines, final ProcessorInput input)
      throws IOException {
    final String artifactName =
        String.format(
            "%s-%s-checkstyle.jsonl",
            input.module().isEmpty() ? "root" : input.module().replace(":", "-").substring(1),
            input.taskName());
    final Path artifactPath =
        Path.of(input.resultsDir()).resolve(input.sessionId()).resolve(artifactName);
    final Path parentDir = artifactPath.getParent();
    if (parentDir != null) {
      Files.createDirectories(parentDir);
    }
    Files.write(artifactPath, jsonLines);

    return new ProcessorResult(
        "SUCCESS", "Converted Checkstyle XML to JSONL: " + artifactName, artifactPath.toString());
  }

  @Override
  public ValidationResult validateConfig(final Map<String, Object> config) {
    final ValidationResult result;
    if (config == null) {
      result = ValidationResult.error("Configuration is required");
    } else {
      final Object reportPath = config.get("reportPath");
      if (reportPath != null && !(reportPath instanceof String)) {
        result =
            ValidationResult.error(
                "Invalid configuration",
                List.of(new ValidationResult.FieldError("reportPath", "Must be a string")));
      } else {
        result = ValidationResult.success();
      }
    }
    return result;
  }
}
