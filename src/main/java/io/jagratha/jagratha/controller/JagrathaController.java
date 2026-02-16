package io.jagratha.jagratha.controller;

import io.jagratha.jagratha.model.FileRequest;
import io.jagratha.jagratha.model.TaskResponse;
import io.jagratha.jagratha.service.JagrathaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for Jagratha operations. Provides endpoints for file management and task
 * execution.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class JagrathaController {

  private final JagrathaService service;

  private static final String SUCCESS_STATUS = "SUCCESS";

  /**
   * Save a file to the external project.
   *
   * @param request the file request containing path and content
   * @return response entity with success or error message
   */
  @PostMapping("/files")
  public Mono<ResponseEntity<String>> saveFile(@Valid @RequestBody final FileRequest request) {
    return service
        .saveFile(request.path(), request.content())
        .thenReturn(ResponseEntity.ok("File saved successfully"))
        .onErrorResume(
            IllegalArgumentException.class,
            e -> Mono.just(ResponseEntity.badRequest().body(e.getMessage())))
        .onErrorResume(
            Exception.class,
            e ->
                Mono.just(
                    ResponseEntity.internalServerError()
                        .body("Failed to save file: " + e.getMessage())));
  }

  /**
   * Run quality checks on the external project and return the results.
   *
   * @return response entity with task status and output
   */
  @PostMapping("/tasks/complete")
  public Mono<ResponseEntity<TaskResponse>> completeTask() {
    return service
        .runQualityChecks()
        .map(
            response -> {
              if (SUCCESS_STATUS.equals(response.status())) {
                return ResponseEntity.ok(response);
              } else {
                return ResponseEntity.status(500).body(response);
              }
            });
  }
}
