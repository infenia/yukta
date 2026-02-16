package io.jagratha.jagratha.service;

import static org.junit.jupiter.api.Assertions.*;

import io.jagratha.jagratha.config.JagrathaConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class JagrathaServiceTest {

  private JagrathaService service;
  private JagrathaConfig config;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    config =
        new JagrathaConfig(new JagrathaConfig.ExternalProject(tempDir.toString(), "./gradlew"));
    service = new JagrathaService(config);
  }

  @Test
  void testSaveFile() throws IOException {
    String relativePath = "src/main/java/Test.java";
    String content = "public class Test {}";

    StepVerifier.create(service.saveFile(relativePath, content)).verifyComplete();

    Path expectedFile = tempDir.resolve(relativePath);
    assertTrue(Files.exists(expectedFile));
    assertEquals(content, Files.readString(expectedFile));
  }

  @Test
  void testSaveFileOutsideRoot() {
    String relativePath = "../outside.java";
    String content = "content";

    StepVerifier.create(service.saveFile(relativePath, content))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testSaveFileNoPathConfigured() {
    config = new JagrathaConfig(null);
    service = new JagrathaService(config);
    StepVerifier.create(service.saveFile("test.java", "content"))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void testSaveFileIOException() {
    // Create a directory where the file should be, to cause IOException on writeString
    String relativePath = "dir";
    try {
      Files.createDirectories(tempDir.resolve(relativePath));
    } catch (IOException e) {
    }

    // Attempting to save a file with same name as existing directory
    StepVerifier.create(service.saveFile(relativePath, "content"))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testRunQualityChecksPathNotConfigured() {
    config = new JagrathaConfig(null);
    service = new JagrathaService(config);

    StepVerifier.create(service.runQualityChecks())
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("not configured"));
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksDirectoryDoesNotExist() {
    config =
        new JagrathaConfig(new JagrathaConfig.ExternalProject("/non/existent/path", "./gradlew"));
    service = new JagrathaService(config);

    StepVerifier.create(service.runQualityChecks())
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("does not exist"));
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksExecutionFailure() {
    // We expect it to fail because there's no gradlew in the tempDir
    StepVerifier.create(service.runQualityChecks())
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("Error executing Gradle"));
            })
        .verifyComplete();
  }
}
