package io.jagratha.jagratha;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.jagratha.jagratha.controller.JagrathaController;
import io.jagratha.jagratha.model.FileRequest;
import io.jagratha.jagratha.model.TaskResponse;
import io.jagratha.jagratha.service.JagrathaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(JagrathaController.class)
class JagrathaControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private JagrathaService service;

  @Test
  void testSaveFile() {
    FileRequest request = new FileRequest("src/Test.java", "content");

    when(service.saveFile(anyString(), anyString())).thenReturn(Mono.empty());

    webTestClient
        .post()
        .uri("/api/files")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("File saved successfully");
  }

  @Test
  void testSaveFileIllegalArgument() {
    FileRequest request = new FileRequest("../Outside.java", "content");

    when(service.saveFile(anyString(), anyString()))
        .thenReturn(Mono.error(new IllegalArgumentException("Invalid path")));

    webTestClient
        .post()
        .uri("/api/files")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .isEqualTo("Invalid path");
  }

  @Test
  void testSaveFileInternalError() {
    FileRequest request = new FileRequest("test.java", "content");

    when(service.saveFile(anyString(), anyString()))
        .thenReturn(Mono.error(new RuntimeException("IO error")));

    webTestClient
        .post()
        .uri("/api/files")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody(String.class)
        .value(containsString("Failed to save file"));
  }

  @Test
  void testCompleteTaskSuccess() {
    TaskResponse response = new TaskResponse("SUCCESS", "Build successful");

    when(service.runQualityChecks()).thenReturn(Mono.just(response));

    webTestClient
        .post()
        .uri("/api/tasks/complete")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("SUCCESS")
        .jsonPath("$.output")
        .isEqualTo("Build successful");
  }

  @Test
  void testCompleteTaskFailure() {
    TaskResponse response = new TaskResponse("FAILURE", "Build failed");

    when(service.runQualityChecks()).thenReturn(Mono.just(response));

    webTestClient
        .post()
        .uri("/api/tasks/complete")
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("FAILURE")
        .jsonPath("$.output")
        .isEqualTo("Build failed");
  }
}
