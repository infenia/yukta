package com.infenia.jagratha;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.controller.AppController;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.FileRequest;
import com.infenia.jagratha.model.TaskRequest;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.service.AppService;
import java.util.List;
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
  @MockitoBean private AppConfigService configService;

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
            "session-1",
            "/new/path",
            "gradle",
            null,
            List.of("test"),
            List.of(),
            300L,
            "/new/logs",
            "/new/results");

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
