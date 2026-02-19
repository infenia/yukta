package com.infenia.jagratha.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class LogRetrievalServiceTest {

  private LogRetrievalService service;
  private AppConfigService configService;

  @TempDir Path tempDir;
  private Path resultsDir;
  private Path fileLogDir;

  @BeforeEach
  void setUp() throws IOException {
    resultsDir = tempDir.resolve("results");
    fileLogDir = tempDir.resolve("files");
    Files.createDirectories(resultsDir);
    Files.createDirectories(fileLogDir);

    configService = mock(AppConfigService.class);
    service = new LogRetrievalService(configService);

    when(configService.getResultLogDir(any())).thenReturn(resultsDir.toString());
    when(configService.getFileLogDir(any())).thenReturn(fileLogDir.toString());
  }

  @Test
  void testListAndGetLogs() throws IOException {
    String sessionId = "session-logs";
    Path sessionResultsDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessionResultsDir);
    Files.writeString(sessionResultsDir.resolve("test.log"), "test content");

    StepVerifier.create(service.listLogs(sessionId))
        .assertNext(logs -> assertTrue(logs.contains("test.log")))
        .verifyComplete();

    StepVerifier.create(service.getLogContent(sessionId, "test.log"))
        .expectNext("test content")
        .verifyComplete();
  }
}
