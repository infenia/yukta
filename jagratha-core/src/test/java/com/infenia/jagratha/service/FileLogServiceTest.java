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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FileLogServiceTest {

  private FileLogService service;
  private AppConfigService configService;

  @TempDir Path tempDir;
  private Path filesDir;
  private Path projectDir;

  @BeforeEach
  void setUp() throws IOException {
    filesDir = tempDir.resolve("files");
    projectDir = tempDir.resolve("project");
    Files.createDirectories(filesDir);
    Files.createDirectories(projectDir);

    configService = mock(AppConfigService.class);
    service = new FileLogService(configService, new ObjectMapper());

    when(configService.getFileLogDir(any())).thenReturn(Mono.just(filesDir.toString()));
    when(configService.getProjectPath(any())).thenReturn(Mono.just(projectDir.toString()));
  }

  @Test
  void testSaveFile() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    Path expectedFile = filesDir.resolve(sessionId).resolve(sessionId + ".log");
    assertTrue(Files.exists(expectedFile));
    String content = Files.readString(expectedFile);
    assertTrue(content.contains("\"path\":\"src/main/java/Test.java\""));
    assertTrue(content.contains("\"status\":\"PENDING\""));
  }

  @Test
  void testSaveFileResetsSuccess() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";
    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(logFile, "{\"path\":\"" + path + "\",\"status\":\"SUCCESS\"}\n");

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    String content = Files.readString(logFile);
    assertTrue(content.contains("\"status\":\"PENDING\""));
  }

  @Test
  void testSaveFileAbsolute() throws IOException {
    String sessionId = "session-abs";
    Path filePath = projectDir.resolve("src/main/java/Abs.java");
    Files.createDirectories(filePath.getParent());
    Files.createFile(filePath);

    StepVerifier.create(service.saveFile(filePath.toString(), sessionId)).verifyComplete();

    Path logFile = filesDir.resolve(sessionId).resolve(sessionId + ".log");
    String content = Files.readString(logFile);
    // Should be relative
    assertTrue(content.contains("\"path\":\"src/main/java/Abs.java\""));
  }

  @Test
  void testSaveFileNoPathConfigured() {
    when(configService.getFileLogDir(anyString())).thenReturn(Mono.just(""));
    StepVerifier.create(service.saveFile("test.java", "session-1"))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void testGetModifiedFiles() throws IOException {
    String sessionId = "session-mod";
    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(logFile, "{\"path\":\"file1.java\",\"status\":\"PENDING\"}\n");

    StepVerifier.create(service.getModifiedFiles(sessionId))
        .assertNext(
            files -> {
              assertEquals(1, files.size());
              assertEquals("PENDING", files.get("file1.java"));
            })
        .verifyComplete();
  }
}
