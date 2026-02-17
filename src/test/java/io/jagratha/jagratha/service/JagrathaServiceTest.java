package io.jagratha.jagratha.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jagratha.jagratha.config.JagrathaConfigService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class JagrathaServiceTest {

  private JagrathaService service;
  private JagrathaConfigService configService;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    configService = mock(JagrathaConfigService.class);
    service = new JagrathaService(configService);

    when(configService.getFileLogDir()).thenReturn(tempDir.resolve("files").toString());
    when(configService.getResultLogDir()).thenReturn(tempDir.resolve("results").toString());
    when(configService.getProjectPath()).thenReturn(tempDir.toString());
    when(configService.getGradlePath()).thenReturn("./gradlew");
    when(configService.getExecutionTimeout()).thenReturn(600L);
  }

  @Test
  void testSaveFile() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    Path expectedFile = tempDir.resolve("files").resolve(sessionId + ".log");
    assertTrue(Files.exists(expectedFile));
    assertEquals(path + System.lineSeparator(), Files.readString(expectedFile));
  }

  @Test
  void testSaveFileNoPathConfigured() {
    when(configService.getFileLogDir()).thenReturn(null);
    StepVerifier.create(service.saveFile("test.java", "session-1"))
        .expectError(IllegalStateException.class)
        .verify();
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
  void testRunQualityChecksDirectoryDoesNotExist() {
    when(configService.getProjectPath()).thenReturn("/non/existent/path");

    StepVerifier.create(service.runQualityChecks("session-1"))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("does not exist"));
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksExecutionFailure() throws IOException {
    // We expect it to fail because there's no gradlew in the tempDir
    StepVerifier.create(service.runQualityChecks("session-1"))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("Error executing Gradle"));

              // Verify results are logged
              Path resultFile = tempDir.resolve("results").resolve("session-1.log");
              assertTrue(Files.exists(resultFile));
            })
        .verifyComplete();
  }
}
