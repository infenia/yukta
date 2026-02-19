/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import reactor.core.publisher.Mono;
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

    when(configService.getResultLogDir(any())).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getFileLogDir(any())).thenReturn(Mono.just(fileLogDir.toString()));
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
