// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.ProjectPathRequest;
import com.infenia.yukta.dto.response.ProjectPathCheck;
import com.infenia.yukta.model.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Whether a working directory exists on the server, so a form can say so before the session is
 * created. A POST, so only a key that may create sessions (ADMIN) can probe the file system with
 * it.
 */
@RestController
@NoArgsConstructor
@Tag(name = "Session API", description = "Check a working directory before creating a session")
public class ProjectPathController {

  /**
   * Look up a working directory.
   *
   * @param request the path
   * @return whether something is there, and whether it is a directory
   */
  @PostMapping("/api/sessions/validate/project-path")
  @Operation(
      summary = "Check a working directory",
      description =
          "Says whether the path exists on the server and is a directory. Nothing else about the"
              + " file system is returned.")
  public Mono<ApiResponse<ProjectPathCheck>> checkProjectPath(
      @Valid @RequestBody final ProjectPathRequest request) {
    // file system calls block, so off the event loop
    return Mono.fromCallable(() -> check(request.projectPath()))
        .subscribeOn(Schedulers.boundedElastic())
        .map(check -> ApiResponse.success(200, "Working directory checked", check));
  }

  private static ProjectPathCheck check(final String projectPath) {
    final Path path;
    try {
      path = Path.of(projectPath);
    } catch (InvalidPathException e) {
      return new ProjectPathCheck(false, false);
    }
    return new ProjectPathCheck(Files.exists(path), Files.isDirectory(path));
  }
}
