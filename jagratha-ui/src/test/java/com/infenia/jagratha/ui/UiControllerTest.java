package com.infenia.jagratha.ui;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.service.FileLogService;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(UiController.class)
class UiControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private SessionService sessionService;
  @MockitoBean private FileLogService fileLogService;
  @MockitoBean private LogRetrievalService logRetrievalService;

  @MockitoBean private AppConfigService configService;

  @MockitoBean private TaskTrackerService taskTrackerService;

  @MockitoBean private gg.jte.TemplateEngine templateEngine;

  @Test
  void testStreamLogs() {
    webTestClient
        .get()
        .uri("/ui/api/sessions/session1/logs/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("text/event-stream");
  }

  @Test
  void testStreamStatus() {
    webTestClient
        .get()
        .uri("/ui/api/sessions/session1/status/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("text/event-stream");
  }

  @Test
  void testIndex() {
    when(sessionService.getActiveSessions()).thenReturn(Flux.empty());
    webTestClient.get().uri("/ui").exchange().expectStatus().isOk();
  }

  @Test
  void testHistory() {
    when(sessionService.getHistorySessions()).thenReturn(Flux.empty());
    webTestClient.get().uri("/ui/history").exchange().expectStatus().isOk();
  }

  @Test
  void testSessionDetails() {
    when(sessionService.getSessionConfig(anyString())).thenReturn(Mono.just(Map.of()));
    when(sessionService.getSessionWorkflows(anyString())).thenReturn(Mono.just(List.of()));
    when(fileLogService.getModifiedFiles(anyString())).thenReturn(Mono.just(Map.of()));
    when(logRetrievalService.listLogs(anyString())).thenReturn(Mono.just(List.of()));
    webTestClient.get().uri("/ui/sessions/session1").exchange().expectStatus().isOk();
  }
}
