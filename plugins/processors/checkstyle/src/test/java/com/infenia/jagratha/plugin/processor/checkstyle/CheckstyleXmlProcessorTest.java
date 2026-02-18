package com.infenia.jagratha.plugin.processor.checkstyle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckstyleXmlProcessorTest {

  private CheckstyleXmlProcessor processor;
  private ObjectMapper objectMapper;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    processor = new CheckstyleXmlProcessor(objectMapper);
  }

  @Test
  void testProcessSuccessful() throws IOException {
    Path projectRoot = tempDir.resolve("project");
    Files.createDirectories(projectRoot);
    Path reportFile = projectRoot.resolve("checkstyle-report.xml");

    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<checkstyle version=\"10.0\">\n"
            + "  <file name=\"/path/to/File.java\">\n"
            + "    <error line=\"1\" column=\"5\" severity=\"warning\" message=\"Message\""
            + " source=\"source\"/>\n"
            + "  </file>\n"
            + "</checkstyle>";
    Files.writeString(reportFile, xml);

    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput(
            "session-1",
            projectRoot.toString(),
            ":module",
            "checkstyleMain",
            "See the report at: file://" + reportFile.toAbsolutePath(),
            tempDir.resolve("results").toString(),
            Map.of());

    OutputProcessorPlugin.ProcessorResult result = processor.process(input);

    assertEquals("SUCCESS", result.status());
    assertTrue(result.output().contains("Converted Checkstyle XML to JSONL"));
    assertTrue(Files.exists(Path.of(result.artifactPath())));

    String jsonl = Files.readString(Path.of(result.artifactPath()));
    assertTrue(jsonl.contains("\"file\":\"/path/to/File.java\""));
    assertTrue(jsonl.contains("\"line\":\"1\""));
    assertTrue(jsonl.contains("\"severity\":\"warning\""));
  }

  @Test
  void testProcessReportNotFound() {
    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput(
            "session-1",
            "/tmp",
            ":module",
            "checkstyleMain",
            "No report here",
            "/tmp/results",
            Map.of());

    OutputProcessorPlugin.ProcessorResult result = processor.process(input);

    assertEquals("FAILURE", result.status());
    assertEquals("Checkstyle report path not found in output or config.", result.output());
  }

  @Test
  void testProcessFileNotFound() {
    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput(
            "session-1",
            "/tmp",
            ":module",
            "checkstyleMain",
            "See the report at: file:///non/existent/report.xml",
            "/tmp/results",
            Map.of());

    OutputProcessorPlugin.ProcessorResult result = processor.process(input);

    assertEquals("FAILURE", result.status());
    assertTrue(result.output().contains("Checkstyle report file does not exist"));
  }
}
