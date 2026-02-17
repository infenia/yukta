package io.jagratha.jagratha;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jagratha.jagratha.config.JagrathaConfigService;
import io.jagratha.jagratha.controller.JagrathaController;
import io.jagratha.jagratha.model.ConfigRequest;
import io.jagratha.jagratha.model.FileRequest;
import io.jagratha.jagratha.model.TaskRequest;
import io.jagratha.jagratha.model.TaskResponse;
import io.jagratha.jagratha.service.JagrathaService;
import java.util.List;
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
  @MockitoBean private JagrathaConfigService configService;

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
    FileRequest request = new FileRequest("../Outside.java", "session-1", null);

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
        .expectBody(String.class)
        .value(containsString("Failed to log file path"));
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
            "session-1", "/new/path", null, List.of("test"), 300L, "/new/logs", "/new/results");

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

    verify(configService).setProjectPath("/new/path");
    verify(configService).setExecutionTimeout(300L);
    verify(configService).setFileLogDir("/new/logs");
    verify(configService).setResultLogDir("/new/results");
  }
}
