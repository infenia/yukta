package com.infenia.jagratha;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.controller.AppController;
import com.infenia.jagratha.mapper.AppConfigMapper;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.FileRequest;
import com.infenia.jagratha.model.PluginRegistration;
import com.infenia.jagratha.model.TaskRequest;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.service.AppService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(AppController.class)
class AppControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private AppService service;
  @MockitoBean private AppConfigMapper configMapper;

  @Test
  void testSaveFile() {
    FileRequest request = new FileRequest("src/Test.java", "session-1", "content");

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
        .isEqualTo("File path logged successfully");
  }

  @Test
  void testSaveFileIllegalArgument() {
    FileRequest request = new FileRequest("Outside.java", "session-1", null);

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
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Invalid path");
  }

  @Test
  void testSaveFileInternalError() {
    FileRequest request = new FileRequest("test.java", "session-1", null);

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
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("An unexpected error occurred: IO error");
  }

  @Test
  void testCompleteTaskSuccess() {
    TaskRequest request = new TaskRequest("session-1");
    TaskResponse response = new TaskResponse("SUCCESS", "Build successful");

    when(service.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    webTestClient
        .post()
        .uri("/api/tasks/complete")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
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
    TaskRequest request = new TaskRequest("session-1");
    TaskResponse response = new TaskResponse("FAILURE", "Build failed");

    when(service.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    webTestClient
        .post()
        .uri("/api/tasks/complete")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("FAILURE")
        .jsonPath("$.output")
        .isEqualTo("Build failed");
  }

  @Test
  void testUpdateConfig() {
    ConfigRequest request =
        new ConfigRequest(
            "session-1",
            "/new/path",
            List.of(new PluginRegistration("gradle", Map.of("key", "value"))),
            List.of(new WorkflowConfig("test", null, null)));

    webTestClient
        .post()
        .uri("/api/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("Configuration updated successfully");

    verify(configMapper).toData(any());
    verify(service).applyConfigOverrides(any());
  }

  @Test
  void testSaveFileValidationError() {
    FileRequest request = new FileRequest("", "invalid session..id", null);

    webTestClient
        .post()
        .uri("/api/files")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Validation failed")
        .jsonPath("$.errors[?(@.field=='path')].message")
        .isEqualTo("Path is required")
        .jsonPath("$.errors[?(@.field=='sessionId')].message")
        .isEqualTo("Invalid session ID format");
  }

  @Test
  void testUpdateConfigValidationError() {
    ConfigRequest request =
        new ConfigRequest(
            "sess", "", // empty project path
            List.of(), // empty plugins
            List.of()); // empty workflows

    webTestClient
        .post()
        .uri("/api/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Validation failed");
  }

  @Test
  void testConstraintViolation() {
    when(service.listLogs(anyString()))
        .thenReturn(
            Mono.error(
                new jakarta.validation.ConstraintViolationException(
                    "Violation", java.util.Set.of())));

    webTestClient
        .get()
        .uri("/api/logs/invalid..id")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Constraint violation");
  }

  @Test
  void testIllegalState() {
    when(service.listLogs(anyString()))
        .thenReturn(Mono.error(new IllegalStateException("Bad state")));

    webTestClient
        .get()
        .uri("/api/logs/sess")
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Bad state");
  }
}
