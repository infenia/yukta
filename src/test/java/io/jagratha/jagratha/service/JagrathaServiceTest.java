package io.jagratha.jagratha.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jagratha.jagratha.config.JagrathaConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class JagrathaServiceTest {

  private JagrathaService service;
  private JagrathaConfigService configService;

  @TempDir Path tempDir;
  private Path filesDir;
  private Path resultsDir;
  private Path projectDir;

  @BeforeEach
  void setUp() throws IOException {
    filesDir = tempDir.resolve("files");
    resultsDir = tempDir.resolve("results");
    projectDir = tempDir.resolve("project");
    Files.createDirectories(filesDir);
    Files.createDirectories(resultsDir);
    Files.createDirectories(projectDir);

    configService = mock(JagrathaConfigService.class);
    service = new JagrathaService(configService);

    when(configService.getFileLogDir()).thenReturn(filesDir.toString());
    when(configService.getResultLogDir()).thenReturn(resultsDir.toString());
    when(configService.getProjectPath()).thenReturn(projectDir.toString());
    when(configService.getGradlePath()).thenReturn("./gradlew");
    when(configService.getExecutionTimeout()).thenReturn(600L);
    when(configService.getTasks()).thenReturn(List.of("test"));
  }

  @Test
  void testSaveFile() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    Path expectedFile = filesDir.resolve(sessionId + ".log");
    assertTrue(Files.exists(expectedFile));
    assertEquals(path + "|PENDING" + System.lineSeparator(), Files.readString(expectedFile));
  }

  @Test
  void testSaveFileResetsSuccess() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";
    Path logFile = filesDir.resolve(sessionId + ".log");
    Files.writeString(logFile, path + "|SUCCESS\n");

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    assertEquals(path + "|PENDING" + System.lineSeparator(), Files.readString(logFile));
  }

  @Test
  void testSaveFileAbsolute() throws IOException {
    String sessionId = "session-abs";
    Path filePath = projectDir.resolve("src/main/java/Abs.java");
    Files.createDirectories(filePath.getParent());
    Files.createFile(filePath);

    StepVerifier.create(service.saveFile(filePath.toString(), sessionId)).verifyComplete();

    Path logFile = filesDir.resolve(sessionId + ".log");
    String content = Files.readString(logFile);
    // Should be relative
    assertEquals("src/main/java/Abs.java|PENDING" + System.lineSeparator(), content);
  }

  @Test
  void testSaveFileNoPathConfigured() {
    when(configService.getFileLogDir()).thenReturn(null);
    StepVerifier.create(service.saveFile("test.java", "session-1"))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void testRunQualityChecksNoChanges() {
    String sessionId = "session-no-changes";

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("SUCCESS", response.status());
              assertEquals("No pending changes to process.", response.output());
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksPathNotConfigured() {
    when(configService.getProjectPath()).thenReturn(null);

    StepVerifier.create(service.runQualityChecks("session-1"))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("not configured"));
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksMultiModule() throws IOException {
    String sessionId = "session-multi";
    Path module1 = projectDir.resolve("module1");
    Files.createDirectories(module1);
    Files.createFile(module1.resolve("build.gradle"));

    Path logFile = filesDir.resolve(sessionId + ".log");
    Files.writeString(
        logFile, "module1/src/Main.java|PENDING\nroot.java|PENDING\n", StandardCharsets.UTF_8);

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("--- Module: :module1 ---"));
              assertTrue(response.output().contains("--- Module: root ---"));
            })
        .verifyComplete();

    // Check that statuses are updated (to FAILURE in this case since gradlew fails to run)
    String content = Files.readString(logFile);
    assertTrue(content.contains("module1/src/Main.java|FAILURE"));
    assertTrue(content.contains("root.java|FAILURE"));
  }

  @Test
  void testRunQualityChecksSkipsSuccess() throws IOException {
    String sessionId = "session-skip";
    Path logFile = filesDir.resolve(sessionId + ".log");
    Files.writeString(
        logFile, "success.java|SUCCESS\npending.java|PENDING\n", StandardCharsets.UTF_8);

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              // Since it's failure, we should see output for pending but NOT for success
              assertTrue(response.output().contains("--- Module: root ---"));
            })
        .verifyComplete();

    String content = Files.readString(logFile);
    assertTrue(content.contains("success.java|SUCCESS"));
    assertTrue(content.contains("pending.java|FAILURE"));
  }
}
