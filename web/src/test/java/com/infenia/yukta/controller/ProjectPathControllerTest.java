// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Tests for the behaviour described by {@link ProjectPathController}. */
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class ProjectPathControllerTest {

  /** A scratch directory. */
  @TempDir private Path dir;

  /** The client. */
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToController(new ProjectPathController())
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private WebTestClient.ResponseSpec check(final String projectPath) {
    return client
        .post()
        .uri("/api/sessions/validate/project-path")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("projectPath", projectPath))
        .exchange();
  }

  private void expect(final String projectPath, final boolean exists, final boolean directory) {
    check(projectPath)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.exists")
        .isEqualTo(exists)
        .jsonPath("$.data.directory")
        .isEqualTo(directory);
  }

  @Test
  void aDirectoryExistsAndIsADirectory() {
    expect(dir.toString(), true, true);
  }

  @Test
  void aFileExistsButIsNoDirectory() throws IOException {
    expect(Files.createFile(dir.resolve("file")).toString(), true, false);
  }

  @Test
  void aMissingPathDoesNotExist() {
    expect(dir.resolve("missing").toString(), false, false);
  }

  @Test
  void aPathTheFileSystemCannotNameDoesNotExist() {
    expect("bad\0path", false, false);
  }

  @Test
  void aBlankPathIsABadRequest() {
    check(" ").expectStatus().isBadRequest();
  }
}
